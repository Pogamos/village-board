package fr.villageboard.village;

import fr.villageboard.VillageBoard;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Répliques des villageois, lues dans config/villageboard/dialogues.txt. Le fichier est créé avec des exemples au
 * premier lancement, puis relu automatiquement dès qu'il est modifié (pas besoin de redémarrer).
 * <p>
 * Format : une réplique par ligne, rangées sous des sections [nom] ; # commence un commentaire. Sections reconnues :
 * [tous], [enfant], un métier ([fermier], [bibliothecaire]… ou l'identifiant vanilla [farmer]), et des situations :
 * [sans_abri], [marie], [veuf], [celibataire], [parent], [nuit], [pluie]. Variables : {joueur}, {nom}, {metier},
 * {village}, {conjoint} ; une réplique qui utilise une variable vide (villageois sans nom, célibataire…) est écartée.
 */
final class Dialogues {

	/** Ce qu'on sait du villageois qui parle, pour choisir une réplique. */
	record Context(boolean named, String profession, boolean baby, boolean homeless, boolean married, boolean widowed,
			boolean hasSpouseName, boolean parent, boolean inVillage, boolean night, boolean raining) {
	}

	private static final Map<String, String> ALIASES = Map.ofEntries(
			Map.entry("armurier", "armorer"),
			Map.entry("boucher", "butcher"),
			Map.entry("cartographe", "cartographer"),
			Map.entry("clerc", "cleric"),
			Map.entry("pretre", "cleric"),
			Map.entry("fermier", "farmer"),
			Map.entry("pecheur", "fisherman"),
			Map.entry("archer", "fletcher"),
			Map.entry("fabricant_de_fleches", "fletcher"),
			Map.entry("maroquinier", "leatherworker"),
			Map.entry("tanneur", "leatherworker"),
			Map.entry("bibliothecaire", "librarian"),
			Map.entry("macon", "mason"),
			Map.entry("tailleur_de_pierre", "mason"),
			Map.entry("berger", "shepherd"),
			Map.entry("outilleur", "toolsmith"),
			Map.entry("fabricant_d_outils", "toolsmith"),
			Map.entry("forgeron", "weaponsmith"),
			Map.entry("fabricant_d_armes", "weaponsmith"),
			Map.entry("niais", "nitwit"),
			Map.entry("sans_emploi", "none"),
			Map.entry("chomeur", "none"),
			Map.entry("all", "tous"),
			Map.entry("child", "enfant"),
			Map.entry("homeless", "sans_abri"),
			Map.entry("married", "marie"),
			Map.entry("widowed", "veuf"),
			Map.entry("single", "celibataire"),
			Map.entry("night", "nuit"),
			Map.entry("rain", "pluie"));

	private final Path file = FabricLoader.getInstance().getConfigDir().resolve("villageboard").resolve("dialogues.txt");
	private final Map<String, List<String>> sections = new HashMap<>();
	private final Random random = new Random();
	private FileTime loadedAt;

	/** Crée le fichier d'exemples s'il n'existe pas, puis le lit. */
	void load() {
		reloadIfChanged();
	}

	/** Une réplique au hasard pour ce villageois (différente de {@code previous} si possible), ou null s'il n'y en a aucune. */
	String pick(Context c, String previous) {
		reloadIfChanged();
		List<String> pool = new ArrayList<>();
		if (c.baby()) {
			pool.addAll(section("enfant"));
			if (pool.isEmpty()) {
				pool.addAll(section("tous"));
			}
		} else {
			pool.addAll(section("tous"));
			pool.addAll(section(c.profession()));
			if (c.homeless()) {
				pool.addAll(section("sans_abri"));
			}
			if (c.married()) {
				pool.addAll(section("marie"));
			} else if (c.widowed()) {
				pool.addAll(section("veuf"));
			} else {
				pool.addAll(section("celibataire"));
			}
			if (c.parent()) {
				pool.addAll(section("parent"));
			}
		}
		if (c.night()) {
			pool.addAll(section("nuit"));
		}
		if (c.raining()) {
			pool.addAll(section("pluie"));
		}
		pool.removeIf(line -> !usable(line, c));
		if (pool.size() > 1) {
			pool.remove(previous);
		}
		return pool.isEmpty() ? null : pool.get(random.nextInt(pool.size()));
	}

	private List<String> section(String key) {
		return sections.getOrDefault(key, List.of());
	}

	/** Une réplique n'est utilisable que si toutes ses variables ont une valeur. */
	private static boolean usable(String line, Context c) {
		return (!line.contains("{nom}") || c.named())
				&& (!line.contains("{conjoint}") || c.married() && c.hasSpouseName())
				&& (!line.contains("{village}") || c.inVillage());
	}

	private void reloadIfChanged() {
		try {
			if (!Files.exists(file)) {
				Files.createDirectories(file.getParent());
				try (InputStream in = Dialogues.class.getResourceAsStream("/villageboard/dialogues_default.txt")) {
					if (in != null) {
						Files.write(file, in.readAllBytes());
					}
				}
			}
			FileTime modified = Files.getLastModifiedTime(file);
			if (modified.equals(loadedAt)) {
				return;
			}
			loadedAt = modified;
			parse(Files.readAllLines(file, StandardCharsets.UTF_8));
			VillageBoard.LOGGER.info("Répliques des villageois chargées : {} section(s)", sections.size());
		} catch (IOException e) {
			VillageBoard.LOGGER.error("Impossible de lire {}", file, e);
		}
	}

	private void parse(List<String> lines) {
		sections.clear();
		String current = "tous";
		for (String raw : lines) {
			String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			if (line.startsWith("[") && line.endsWith("]")) {
				current = key(line.substring(1, line.length() - 1));
				continue;
			}
			sections.computeIfAbsent(current, k -> new ArrayList<>()).add(line);
		}
	}

	/** « Bibliothécaire », « minecraft:librarian », « librarian » → « librarian ». */
	private static String key(String section) {
		String k = Normalizer.normalize(section.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
				.replaceAll("\\p{M}", "")
				.replaceAll("['’ -]+", "_");
		if (k.startsWith("minecraft:")) {
			k = k.substring("minecraft:".length());
		}
		return ALIASES.getOrDefault(k, k);
	}

	/** Clé de section du métier d'un villageois : « minecraft:farmer » → « farmer ». */
	static String professionKey(String profession) {
		return key(profession);
	}
}
