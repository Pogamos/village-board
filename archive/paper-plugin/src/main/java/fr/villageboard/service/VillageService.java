package fr.villageboard.service;

import fr.villageboard.Settings;
import fr.villageboard.Text;
import fr.villageboard.VillageBoardPlugin;
import fr.villageboard.model.NewsEntry;
import fr.villageboard.model.NewsType;
import fr.villageboard.model.Village;
import fr.villageboard.model.VillagerRecord;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;
import org.bukkit.event.entity.VillagerCareerChangeEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.text.Normalizer;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Registre des villages et de leurs habitants. Toutes les méthodes s'exécutent sur le thread principal.
 */
public final class VillageService {

    private final Settings settings;
    private final VillageStore store;
    private final NamespacedKey nameKey;
    private final NamespacedKey lockKey;
    private final Map<String, Village> villages = new LinkedHashMap<>();
    /** Village auquel appartient chaque villageois recensé. */
    private final Map<UUID, Village> index = new HashMap<>();

    public VillageService(VillageBoardPlugin plugin, Settings settings) {
        this.settings = settings;
        this.store = new VillageStore(plugin, new File(plugin.getDataFolder(), "villages"));
        this.nameKey = new NamespacedKey(plugin, "name");
        this.lockKey = new NamespacedKey(plugin, "locked");
    }

    // ---------------------------------------------------------------- villages

    public void load() {
        for (Village v : store.loadAll()) {
            villages.put(v.id, v);
            v.villagers.keySet().forEach(uuid -> index.put(uuid, v));
        }
    }

    public void saveAll(boolean async) {
        for (Village v : villages.values()) {
            if (v.dirty) {
                v.dirty = false;
                store.save(v, async);
            }
        }
    }

    public Collection<Village> villages() {
        return Collections.unmodifiableCollection(villages.values());
    }

    public Village village(String id) {
        return villages.get(id.toLowerCase(Locale.ROOT));
    }

    /** Le village le plus proche dont le rayon contient {@code loc}. */
    public Village villageAt(Location loc) {
        Village best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (Village v : villages.values()) {
            double d = v.distanceSq(loc);
            if (d <= (double) v.radius * v.radius && d < bestDist) {
                best = v;
                bestDist = d;
            }
        }
        return best;
    }

    public Village boardAt(Block block) {
        for (Village v : villages.values()) {
            if (v.isBoard(block)) {
                return v;
            }
        }
        return null;
    }

    public Village villageOf(UUID villager) {
        return index.get(villager);
    }

    public VillagerRecord record(UUID villager) {
        Village v = index.get(villager);
        return v == null ? null : v.villagers.get(villager);
    }

    public Village create(String name, Block board, int radius) {
        String base = slug(name);
        String id = base;
        for (int i = 2; villages.containsKey(id); i++) {
            id = base + "-" + i;
        }
        Village v = new Village(id);
        v.name = name;
        v.world = board.getWorld().getName();
        v.x = board.getX();
        v.y = board.getY();
        v.z = board.getZ();
        v.radius = radius;
        villages.put(id, v);
        for (Villager villager : board.getWorld().getEntitiesByClass(Villager.class)) {
            if (v.contains(villager.getLocation()) && !index.containsKey(villager.getUniqueId())) {
                register(v, villager);
            }
        }
        news(v, NewsType.INFO, "Fondation de la mairie de " + name + " : " + v.villagers.size() + " habitants recensés.");
        saveAll(false);
        return v;
    }

    public void delete(Village v) {
        villages.remove(v.id);
        v.villagers.keySet().forEach(index::remove);
        store.delete(v);
    }

    public void setRadius(Village v, int radius) {
        v.radius = radius;
        v.dirty = true;
    }

    // ---------------------------------------------------------------- recensement

    /** Recense tous les villageois chargés des mondes qui ont un village. */
    public void scan() {
        Set<String> worlds = villages.values().stream().map(v -> v.world).collect(Collectors.toSet());
        for (String name : worlds) {
            World world = Bukkit.getWorld(name);
            if (world == null) {
                continue;
            }
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                observe(villager, true);
            }
        }
    }

    /**
     * Met à jour la fiche d'un villageois vu dans le monde. L'inscrit s'il se trouve dans un village,
     * et gère les déménagements d'un village à l'autre.
     *
     * @return la fiche, ou null si le villageois n'appartient à aucun village
     */
    public VillagerRecord observe(Villager villager, boolean announce) {
        UUID uuid = villager.getUniqueId();
        Location loc = villager.getLocation();
        Village current = index.get(uuid);
        if (current == null) {
            Village inside = villageAt(loc);
            if (inside == null) {
                return null;
            }
            VillagerRecord r = register(inside, villager);
            if (announce) {
                news(inside, NewsType.ARRIVAL, r.displayName() + " s'installe au village.");
            }
            return r;
        }
        VillagerRecord r = current.villagers.get(uuid);
        if (!current.contains(loc)) {
            Village inside = villageAt(loc);
            if (inside != null) {
                current.villagers.remove(uuid);
                current.dirty = true;
                inside.villagers.put(uuid, r);
                index.put(uuid, inside);
                news(current, NewsType.DEPARTURE, r.displayName() + " déménage à " + inside.name + ".");
                news(inside, NewsType.ARRIVAL, r.displayName() + " arrive de " + current.name + ".");
            }
        }
        refresh(r, villager);
        return r;
    }

    public VillagerRecord register(Village village, Villager villager) {
        VillagerRecord r = new VillagerRecord(villager.getUniqueId());
        r.generatedName = generatedName(villager, village);
        r.firstSeen = System.currentTimeMillis();
        r.firstSeenDay = village.currentDay();
        village.villagers.put(r.uuid, r);
        index.put(r.uuid, village);
        refresh(r, villager);
        return r;
    }

    public void refresh(VillagerRecord r, Villager villager) {
        Component custom = villager.customName();
        r.customName = custom == null ? null : Text.plain(custom);
        r.profession = Professions.key(villager.getProfession());
        r.level = villager.getVillagerLevel();
        r.baby = !villager.isAdult();
        r.locked = isLocked(villager);
        Location loc = villager.getLocation();
        r.world = loc.getWorld().getName();
        r.x = loc.getX();
        r.y = loc.getY();
        r.z = loc.getZ();
        r.lastSeen = System.currentTimeMillis();
        Village v = index.get(r.uuid);
        if (v != null) {
            v.dirty = true;
        }
    }

    public void forget(UUID villager) {
        Village v = index.remove(villager);
        if (v != null) {
            v.villagers.remove(villager);
            v.dirty = true;
        }
    }

    public void news(Village village, NewsType type, String text) {
        village.news.addFirst(new NewsEntry(System.currentTimeMillis(), village.currentDay(), type, text));
        while (village.news.size() > settings.maxNews()) {
            village.news.removeLast();
        }
        village.dirty = true;
    }

    // ---------------------------------------------------------------- événements

    public void recordBirth(Villager child, String parents) {
        Village village = villageAt(child.getLocation());
        if (village == null || index.containsKey(child.getUniqueId())) {
            return;
        }
        VillagerRecord r = register(village, child);
        r.born = true;
        r.parents = parents;
        news(village, NewsType.BIRTH, "Naissance de " + r.displayName() + " !"
                + (parents != null ? " Ses parents : " + parents + "." : ""));
    }

    public void recordDeath(Villager villager, String cause) {
        VillagerRecord r = observe(villager, false);
        if (r == null) {
            return;
        }
        Village village = index.get(r.uuid);
        String who = r.displayName() + " (" + Professions.describe(r).toLowerCase(Locale.FRENCH) + ")";
        news(village, NewsType.DEATH, "Décès de " + who + ". Cause : " + cause + ".");
        forget(r.uuid);
    }

    public void recordCareerChange(Villager villager, String newProfession, VillagerCareerChangeEvent.ChangeReason reason) {
        VillagerRecord r = observe(villager, true);
        if (r == null) {
            return;
        }
        Village village = index.get(r.uuid);
        String old = r.profession;
        r.profession = newProfession;
        if (reason == VillagerCareerChangeEvent.ChangeReason.EMPLOYED) {
            news(village, NewsType.JOB, r.displayName() + " devient "
                    + Professions.info(newProfession).label().toLowerCase(Locale.FRENCH) + ".");
        } else if (!old.equals("none")) {
            news(village, NewsType.JOB_LOST, r.displayName() + " n'est plus "
                    + Professions.info(old).label().toLowerCase(Locale.FRENCH) + ".");
        }
    }

    public void recordTransform(Villager villager, EntityType into) {
        UUID uuid = villager.getUniqueId();
        Village village = index.containsKey(uuid) ? index.get(uuid) : villageAt(villager.getLocation());
        if (village == null) {
            return;
        }
        String name = displayName(villager);
        String text = switch (into) {
            case ZOMBIE_VILLAGER -> name + " est désormais un zombie-villageois.";
            case WITCH -> "Coup de foudre : " + name + " est désormais une sorcière.";
            default -> name + " a quitté le village (transformation).";
        };
        news(village, NewsType.DEATH, text);
        forget(uuid);
    }

    public void recordCure(Villager villager) {
        Village village = villageAt(villager.getLocation());
        if (village == null) {
            return;
        }
        VillagerRecord r = index.containsKey(villager.getUniqueId())
                ? record(villager.getUniqueId())
                : register(village, villager);
        news(village, NewsType.ARRIVAL, "Guérison de " + r.displayName() + " : ancien zombie-villageois, bienvenue au village !");
    }

    // ---------------------------------------------------------------- nom & verrou

    public String displayName(Villager villager) {
        Component custom = villager.customName();
        if (custom != null) {
            return Text.plain(custom);
        }
        VillagerRecord r = record(villager.getUniqueId());
        if (r != null) {
            return r.displayName();
        }
        String stored = villager.getPersistentDataContainer().get(nameKey, PersistentDataType.STRING);
        return stored != null ? stored : "Un villageois";
    }

    public boolean isLocked(Villager villager) {
        return villager.getPersistentDataContainer().getOrDefault(lockKey, PersistentDataType.BOOLEAN, false);
    }

    public void setLocked(Villager villager, boolean locked) {
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        if (locked) {
            pdc.set(lockKey, PersistentDataType.BOOLEAN, true);
        } else {
            pdc.remove(lockKey);
        }
        VillagerRecord r = record(villager.getUniqueId());
        if (r != null) {
            refresh(r, villager);
        }
    }

    /** Le prénom est aussi stocké sur l'entité : il survit à une perte du registre. */
    private String generatedName(Villager villager, Village village) {
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        String stored = pdc.get(nameKey, PersistentDataType.STRING);
        if (stored != null) {
            return stored;
        }
        if (!settings.autoNames()) {
            return "Villageois " + villager.getUniqueId().toString().substring(0, 4);
        }
        Set<String> used = village.villagers.values().stream()
                .map(r -> r.generatedName)
                .collect(Collectors.toSet());
        String name = Names.pick(used);
        pdc.set(nameKey, PersistentDataType.STRING, name);
        return name;
    }

    private static String slug(String name) {
        String s = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return s.isEmpty() ? "village" : s;
    }
}
