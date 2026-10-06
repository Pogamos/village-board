package fr.villageboard.village;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import fr.villageboard.VillageBoard;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * État civil de tous les villages : qui est l'enfant de qui, qui est marié à qui. Une entrée est créée pour chaque
 * naissance, chaque parent et chaque marié ; elle n'est jamais effacée (on garde les morts et les partis).
 * Sauvegardé dans &lt;monde&gt;/villageboard/family.json. Un villageois sans entrée n'a pas de famille connue.
 * <p>
 * Règles des couples : un villageois marié n'a d'enfants qu'avec son conjoint ; deux célibataires qui ont un enfant
 * sont mariés d'office. Le couple prend fin à la mort de l'un des deux, ou par un divorce prononcé au tableau.
 * Pas d'enfant entre un parent et son enfant, ni entre frères et sœurs (demi-frères et demi-sœurs compris).
 */
final class Genealogy {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Kin>>() {
	}.getType();
	/** Version du fichier : 1 = carte brute des entrées (v0.8), 2 = avec les couples (v0.9). */
	private static final int VERSION = 2;
	/** Générations transmises au client autour des habitants d'un village (grands-parents, petits-enfants). */
	private static final int VIEW_DEPTH = 2;

	private final Path file;
	private final Map<String, Kin> entries = new LinkedHashMap<>();
	private int loadedVersion = VERSION;
	private boolean dirty;

	Genealogy(Path file) {
		this.file = file;
	}

	void load() {
		if (!Files.exists(file)) {
			return;
		}
		try {
			JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
			Map<String, Kin> data;
			if (root.isJsonObject() && root.getAsJsonObject().has("people")) {
				JsonObject object = root.getAsJsonObject();
				loadedVersion = object.has("version") ? object.get("version").getAsInt() : VERSION;
				data = GSON.fromJson(object.get("people"), DATA_TYPE);
			} else {
				loadedVersion = 1;
				data = GSON.fromJson(root, DATA_TYPE);
			}
			if (data != null) {
				entries.putAll(data);
				entries.values().forEach(Genealogy::fillDefaults);
			}
		} catch (IOException | RuntimeException e) {
			VillageBoard.LOGGER.error("Impossible de lire {}", file, e);
		}
	}

	/** Champs absents des fichiers plus anciens. */
	private static void fillDefaults(Kin k) {
		if (k.fate == null) {
			k.fate = Kin.Fate.ALIVE;
		}
		if (k.type == null) {
			k.type = Professions.DEFAULT_TYPE;
		}
		if (k.parents == null) {
			k.parents = new ArrayList<>();
		}
		if (k.divorced == null) {
			k.divorced = new ArrayList<>();
		}
		if (k.widowed == null) {
			k.widowed = new ArrayList<>();
		}
	}

	void save(boolean force) {
		if (!dirty && !force || entries.isEmpty() && !Files.exists(file)) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
			JsonObject root = new JsonObject();
			root.addProperty("version", VERSION);
			root.add("people", GSON.toJsonTree(entries, DATA_TYPE));
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			dirty = false;
		} catch (IOException e) {
			VillageBoard.LOGGER.error("Impossible d'enregistrer {}", file, e);
		}
	}

	Kin get(UUID uuid) {
		return entries.get(uuid.toString());
	}

	/** Crée l'entrée d'un villageois (s'il n'en a pas) et la met à jour avec ce qu'on sait de lui. */
	Kin touch(UUID uuid, String name, String profession, String type, boolean baby, String village) {
		Kin k = entries.computeIfAbsent(uuid.toString(), key -> new Kin());
		k.name = name;
		k.profession = profession;
		k.type = type;
		k.baby = baby;
		if (village != null) {
			k.village = village;
		}
		k.fate = Kin.Fate.ALIVE;
		dirty = true;
		return k;
	}

	void birth(UUID child, List<UUID> parents, String name, String type, String village, long day) {
		Kin k = entries.computeIfAbsent(child.toString(), key -> new Kin());
		k.name = name;
		k.type = type;
		k.baby = true;
		k.born = day;
		k.village = village;
		k.parents = parents.stream().map(UUID::toString).toList();
		dirty = true;
	}

	/** Villageois vu vivant : met à jour son entrée s'il en a une (nom, métier, type, village). */
	void update(VillagerRecord r, String village) {
		Kin k = entries.get(r.uuid);
		if (k == null) {
			return;
		}
		String name = r.displayName();
		String type = r.type == null ? Professions.DEFAULT_TYPE : r.type;
		if (!name.equals(k.name) || !r.profession.equals(k.profession) || !type.equals(k.type) || r.baby != k.baby
				|| !village.equals(k.village) || k.fate != Kin.Fate.ALIVE) {
			k.name = name;
			k.profession = r.profession;
			k.type = type;
			k.baby = r.baby;
			k.village = village;
			k.fate = Kin.Fate.ALIVE;
			dirty = true;
		}
	}

	/** Note ce qu'est devenu un villageois ; sa mort rend son conjoint veuf (libre de se remarier). */
	void fate(UUID uuid, Kin.Fate fate, long day) {
		Kin k = entries.get(uuid.toString());
		if (k == null || k.fate == fate) {
			return;
		}
		k.fate = fate;
		k.fateDay = day;
		if (fate == Kin.Fate.DEAD && k.spouse != null) {
			Kin partner = entries.get(k.spouse);
			if (partner != null && uuid.toString().equals(partner.spouse)) {
				partner.spouse = null;
				partner.widowed.add(uuid.toString());
			}
		}
		dirty = true;
	}

	// ------------------------------------------------------------------ couples

	/** Conjoint actuel, ou null. */
	UUID spouse(UUID uuid) {
		Kin k = get(uuid);
		return k == null || k.spouse == null ? null : UUID.fromString(k.spouse);
	}

	/** Nombre d'enfants connus. */
	int childCount(UUID uuid) {
		String key = uuid.toString();
		return (int) entries.values().stream().filter(k -> k.parents.contains(key)).count();
	}

	/** Nombre de frères et sœurs connus (demi-frères et demi-sœurs compris). */
	int siblingCount(UUID uuid) {
		Kin me = get(uuid);
		if (me == null || me.parents.isEmpty()) {
			return 0;
		}
		return (int) entries.values().stream()
				.filter(k -> k != me && k.parents.stream().anyMatch(me.parents::contains))
				.count();
	}

	/** Parent et enfant, ou frères et sœurs (au moins un parent commun). */
	boolean related(UUID a, UUID b) {
		Kin ka = get(a);
		Kin kb = get(b);
		if (ka != null && ka.parents.contains(b.toString()) || kb != null && kb.parents.contains(a.toString())) {
			return true;
		}
		return ka != null && kb != null && ka.parents.stream().anyMatch(kb.parents::contains);
	}

	/** Ces deux villageois peuvent-ils avoir un enfant ensemble ? */
	boolean allowed(UUID a, UUID b) {
		Kin ka = get(a);
		Kin kb = get(b);
		if (ka != null && ka.spouse != null && !ka.spouse.equals(b.toString())) {
			return false;
		}
		if (kb != null && kb.spouse != null && !kb.spouse.equals(a.toString())) {
			return false;
		}
		return !related(a, b);
	}

	/** Marie deux villageois qui ont déjà une entrée (voir {@link #touch}). */
	void marry(UUID a, UUID b, long day) {
		Kin ka = get(a);
		Kin kb = get(b);
		ka.spouse = b.toString();
		kb.spouse = a.toString();
		ka.marriedDay = day;
		kb.marriedDay = day;
		dirty = true;
	}

	/** Divorce : chacun reprend sa liberté. @return l'ex-conjoint, ou null si ce villageois n'était pas marié */
	UUID divorce(UUID a) {
		Kin ka = get(a);
		if (ka == null || ka.spouse == null) {
			return null;
		}
		UUID b = UUID.fromString(ka.spouse);
		ka.spouse = null;
		ka.divorced.add(b.toString());
		Kin kb = get(b);
		if (kb != null && a.toString().equals(kb.spouse)) {
			kb.spouse = null;
			kb.divorced.add(a.toString());
		}
		dirty = true;
		return b;
	}

	/**
	 * Entrées utiles au tableau d'un village : ses habitants et les anciens habitants, plus leurs parents, enfants
	 * et conjoints sur {@link #VIEW_DEPTH} générations (même s'ils vivent ailleurs).
	 */
	Map<String, Kin> forVillage(String village, Set<String> residents) {
		Set<String> keep = new HashSet<>();
		entries.forEach((uuid, k) -> {
			if (village.equals(k.village) || residents.contains(uuid)) {
				keep.add(uuid);
			}
		});
		for (int depth = 0; depth < VIEW_DEPTH; depth++) {
			Set<String> more = new HashSet<>();
			entries.forEach((uuid, k) -> {
				List<String> links = k.links();
				if (keep.contains(uuid)) {
					links.stream().filter(entries::containsKey).forEach(more::add);
				} else if (links.stream().anyMatch(keep::contains)) {
					more.add(uuid);
				}
			});
			keep.addAll(more);
		}
		Map<String, Kin> result = new LinkedHashMap<>();
		entries.forEach((uuid, k) -> {
			if (keep.contains(uuid)) {
				result.put(uuid, k);
			}
		});
		return result;
	}

	// ------------------------------------------------------------------ reprise des anciennes données

	/** Reprend les données des versions précédentes ; à appeler une fois les villages chargés. */
	void migrate(Collection<Village> villages) {
		migrateBirths(villages);
		if (loadedVersion < 2) {
			migrateCouples();
			loadedVersion = VERSION;
			dirty = true;
		}
	}

	/**
	 * Naissances enregistrées avant l'état civil : seuls les noms des parents étaient notés (« Anne &amp; Côme »).
	 * On retrouve chaque parent quand un seul habitant porte ce nom.
	 */
	private void migrateBirths(Collection<Village> villages) {
		Map<String, VillagerRecord> byName = new LinkedHashMap<>();
		Set<String> ambiguous = new HashSet<>();
		Map<String, String> villageOf = new LinkedHashMap<>();
		for (Village v : villages) {
			for (VillagerRecord r : v.villagers.values()) {
				villageOf.put(r.uuid, v.id);
				if (r.customName != null && !r.customName.isEmpty() && byName.put(r.customName, r) != null) {
					ambiguous.add(r.customName);
				}
			}
		}
		for (Village v : villages) {
			for (VillagerRecord r : v.villagers.values()) {
				if (!r.born || r.parents == null || r.parents.isEmpty() || entries.containsKey(r.uuid)) {
					continue;
				}
				List<VillagerRecord> found = java.util.Arrays.stream(r.parents.split(" & "))
						.filter(n -> byName.containsKey(n) && !ambiguous.contains(n))
						.map(byName::get)
						.filter(p -> !p.uuid.equals(r.uuid))
						.toList();
				if (found.isEmpty()) {
					continue;
				}
				for (VillagerRecord p : found) {
					touch(UUID.fromString(p.uuid), p.displayName(), p.profession, typeOf(p), p.baby, villageOf.get(p.uuid));
				}
				birth(UUID.fromString(r.uuid), found.stream().map(p -> UUID.fromString(p.uuid)).toList(),
						r.displayName(), typeOf(r), v.id, r.firstSeenDay);
				Kin k = entries.get(r.uuid);
				k.profession = r.profession;
				k.baby = r.baby;
			}
		}
	}

	private static String typeOf(VillagerRecord r) {
		return r.type == null ? Professions.DEFAULT_TYPE : r.type;
	}

	/** v0.8 → v0.9 : les parents encore en vie et célibataires sont mariés, en commençant par l'enfant le plus jeune. */
	private void migrateCouples() {
		List<Kin> children = new ArrayList<>(entries.values().stream().filter(k -> k.parents.size() >= 2).toList());
		children.sort(Comparator.comparingLong((Kin k) -> k.born).reversed());
		for (Kin child : children) {
			Kin a = entries.get(child.parents.get(0));
			Kin b = entries.get(child.parents.get(1));
			if (a != null && b != null && a != b && a.spouse == null && b.spouse == null
					&& a.fate == Kin.Fate.ALIVE && b.fate == Kin.Fate.ALIVE) {
				marry(UUID.fromString(child.parents.get(0)), UUID.fromString(child.parents.get(1)), Math.max(0, child.born));
			}
		}
	}
}
