package fr.villageboard.service;

import fr.villageboard.model.Village;
import fr.villageboard.model.VillagerRecord;
import org.bukkit.Material;
import org.bukkit.entity.Villager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Libellés français et icônes des métiers, et catégories du tableau. */
public final class Professions {

    public record Info(String key, String label, Material icon) {
    }

    public record Category(String key, String label, Material icon, Predicate<VillagerRecord> filter) {
    }

    private static final Map<String, Info> BY_KEY = new LinkedHashMap<>();
    private static final String[] LEVELS = {"Novice", "Apprenti", "Compagnon", "Expert", "Maître"};

    static {
        add("armorer", "Armurier", Material.BLAST_FURNACE);
        add("butcher", "Boucher", Material.SMOKER);
        add("cartographer", "Cartographe", Material.CARTOGRAPHY_TABLE);
        add("cleric", "Prêtre", Material.BREWING_STAND);
        add("farmer", "Fermier", Material.COMPOSTER);
        add("fisherman", "Pêcheur", Material.BARREL);
        add("fletcher", "Flécheron", Material.FLETCHING_TABLE);
        add("leatherworker", "Tanneur", Material.CAULDRON);
        add("librarian", "Bibliothécaire", Material.LECTERN);
        add("mason", "Maçon", Material.STONECUTTER);
        add("shepherd", "Berger", Material.LOOM);
        add("toolsmith", "Forgeron d'outils", Material.SMITHING_TABLE);
        add("weaponsmith", "Forgeron d'armes", Material.GRINDSTONE);
        add("nitwit", "Niais", Material.GREEN_WOOL);
        add("none", "Sans emploi", Material.WHITE_BED);
    }

    private Professions() {
    }

    private static void add(String key, String label, Material icon) {
        BY_KEY.put(key, new Info(key, label, icon));
    }

    public static Info info(String key) {
        Info info = BY_KEY.get(key);
        if (info != null) {
            return info;
        }
        String pretty = key.isEmpty() ? key : Character.toUpperCase(key.charAt(0)) + key.substring(1).replace('_', ' ');
        return new Info(key, pretty, Material.EMERALD);
    }

    public static String key(Villager.Profession profession) {
        return profession.getKey().getKey();
    }

    public static String levelName(int level) {
        return LEVELS[Math.clamp(level, 1, 5) - 1];
    }

    /** Description courte : « Bibliothécaire · Apprenti », « Enfant », « Sans emploi »… */
    public static String describe(VillagerRecord r) {
        if (r.baby) {
            return "Enfant";
        }
        String label = info(r.profession).label();
        return r.employed() ? label + " · " + levelName(r.level) : label;
    }

    public static Material icon(VillagerRecord r) {
        return r.baby ? Material.CAKE : info(r.profession).icon();
    }

    /** Tous, enfants, sans emploi, métiers par ordre alphabétique, métiers inconnus, niais. */
    public static List<Category> categories(Village village) {
        List<Category> result = new ArrayList<>();
        result.add(new Category("all", "Tous les habitants", Material.VILLAGER_SPAWN_EGG, r -> true));
        result.add(new Category("children", "Enfants", Material.CAKE, r -> r.baby));
        result.add(adults(info("none")));

        List<Info> jobs = new ArrayList<>(BY_KEY.values().stream()
                .filter(i -> !i.key().equals("none") && !i.key().equals("nitwit"))
                .toList());
        village.villagers.values().stream()
                .map(r -> r.profession)
                .distinct()
                .filter(k -> !BY_KEY.containsKey(k))
                .forEach(k -> jobs.add(info(k)));
        jobs.sort(Comparator.comparing(Info::label));
        jobs.forEach(i -> result.add(adults(i)));

        result.add(adults(info("nitwit")));
        return result;
    }

    private static Category adults(Info info) {
        return new Category(info.key(), info.label(), info.icon(), r -> !r.baby && r.profession.equals(info.key()));
    }
}
