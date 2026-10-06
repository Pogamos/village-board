package fr.villageboard.service;

import fr.villageboard.model.NewsEntry;
import fr.villageboard.model.NewsType;
import fr.villageboard.model.Village;
import fr.villageboard.model.VillagerRecord;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** Un fichier YAML par village dans plugins/VillageBoard/villages/. */
final class VillageStore {

    private final Plugin plugin;
    private final File dir;

    VillageStore(Plugin plugin, File dir) {
        this.plugin = plugin;
        this.dir = dir;
    }

    List<Village> loadAll() {
        List<Village> result = new ArrayList<>();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".yml"));
        if (files == null) {
            return result;
        }
        for (File file : files) {
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(file);
                result.add(read(file.getName().replaceFirst("\\.yml$", ""), yaml));
            } catch (IOException | InvalidConfigurationException | IllegalArgumentException e) {
                plugin.getLogger().log(Level.SEVERE, "Impossible de lire " + file.getName(), e);
            }
        }
        return result;
    }

    /** Sérialise sur le thread principal ; l'écriture disque peut se faire en asynchrone. */
    void save(Village village, boolean async) {
        String content = write(village).saveToString();
        Path target = new File(dir, village.id + ".yml").toPath();
        Runnable io = () -> {
            try {
                Files.createDirectories(target.getParent());
                Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                Files.writeString(tmp, content, StandardCharsets.UTF_8);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Impossible d'enregistrer le village " + village.id, e);
            }
        };
        if (async) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, io);
        } else {
            io.run();
        }
    }

    void delete(Village village) {
        File file = new File(dir, village.id + ".yml");
        if (file.exists() && !file.delete()) {
            plugin.getLogger().warning("Impossible de supprimer " + file);
        }
    }

    private static YamlConfiguration write(Village v) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("name", v.name);
        yaml.set("board.world", v.world);
        yaml.set("board.x", v.x);
        yaml.set("board.y", v.y);
        yaml.set("board.z", v.z);
        yaml.set("radius", v.radius);
        for (VillagerRecord r : v.villagers.values()) {
            ConfigurationSection s = yaml.createSection("villagers." + r.uuid);
            s.set("name", r.generatedName);
            s.set("custom-name", r.customName);
            s.set("profession", r.profession);
            s.set("level", r.level);
            s.set("baby", r.baby);
            s.set("locked", r.locked);
            s.set("world", r.world);
            s.set("x", Math.round(r.x * 10) / 10.0);
            s.set("y", Math.round(r.y * 10) / 10.0);
            s.set("z", Math.round(r.z * 10) / 10.0);
            s.set("first-seen", r.firstSeen);
            s.set("first-seen-day", r.firstSeenDay);
            s.set("born", r.born);
            s.set("parents", r.parents);
            s.set("last-seen", r.lastSeen);
        }
        List<Map<String, Object>> news = new ArrayList<>();
        for (NewsEntry n : v.news) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time", n.time());
            m.put("day", n.day());
            m.put("type", n.type().name());
            m.put("text", n.text());
            news.add(m);
        }
        yaml.set("news", news);
        return yaml;
    }

    private static Village read(String id, YamlConfiguration yaml) {
        Village v = new Village(id);
        v.name = yaml.getString("name", id);
        v.world = yaml.getString("board.world", "world");
        v.x = yaml.getInt("board.x");
        v.y = yaml.getInt("board.y");
        v.z = yaml.getInt("board.z");
        v.radius = yaml.getInt("radius", 96);
        ConfigurationSection villagers = yaml.getConfigurationSection("villagers");
        if (villagers != null) {
            for (String key : villagers.getKeys(false)) {
                ConfigurationSection s = villagers.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                VillagerRecord r = new VillagerRecord(UUID.fromString(key));
                r.generatedName = s.getString("name", "Villageois");
                r.customName = s.getString("custom-name");
                r.profession = s.getString("profession", "none");
                r.level = s.getInt("level", 1);
                r.baby = s.getBoolean("baby");
                r.locked = s.getBoolean("locked");
                r.world = s.getString("world");
                r.x = s.getDouble("x");
                r.y = s.getDouble("y");
                r.z = s.getDouble("z");
                r.firstSeen = s.getLong("first-seen");
                r.firstSeenDay = s.getLong("first-seen-day");
                r.born = s.getBoolean("born");
                r.parents = s.getString("parents");
                r.lastSeen = s.getLong("last-seen");
                v.villagers.put(r.uuid, r);
            }
        }
        for (Map<?, ?> m : yaml.getMapList("news")) {
            try {
                v.news.addLast(new NewsEntry(
                        ((Number) m.get("time")).longValue(),
                        ((Number) m.get("day")).longValue(),
                        NewsType.valueOf(String.valueOf(m.get("type"))),
                        String.valueOf(m.get("text"))));
            } catch (RuntimeException ignored) {
                // entrée corrompue : on l'ignore
            }
        }
        return v;
    }
}
