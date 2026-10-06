package fr.villageboard.village;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import fr.villageboard.VillageBoard;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * État civil de tous les villages : qui est l'enfant de qui. Une entrée est créée pour chaque naissance et pour chaque
 * parent ; elle n'est jamais effacée (on garde les morts et les partis). Sauvegardé dans &lt;monde&gt;/villageboard/family.json.
 * Un villageois sans entrée n'a pas de famille connue (arrivé adulte, ou né avant l'installation du mod).
 */
final class Genealogy {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Kin>>() {
	}.getType();
	/** Générations transmises au client autour des habitants d'un village (grands-parents, petits-enfants). */
	private static final int VIEW_DEPTH = 2;

	private final Path file;
	private final Map<String, Kin> entries = new LinkedHashMap<>();
	private boolean dirty;

	Genealogy(Path file) {
		this.file = file;
	}

	void load() {
		if (!Files.exists(file)) {
			return;
		}
		try {
			Map<String, Kin> data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), DATA_TYPE);
			if (data != null) {
				entries.putAll(data);
				entries.values().forEach(k -> {
					if (k.fate == null) {
						k.fate = Kin.Fate.ALIVE;
					}
				});
			}
		} catch (IOException | RuntimeException e) {
			VillageBoard.LOGGER.error("Impossible de lire {}", file, e);
		}
	}

	void save(boolean force) {
		if (!dirty && !force || entries.isEmpty() && !Files.exists(file)) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(entries, DATA_TYPE), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			dirty = false;
		} catch (IOException e) {
			VillageBoard.LOGGER.error("Impossible d'enregistrer {}", file, e);
		}
	}

	/** Parent au moment de la reproduction : crée son entrée s'il n'en a pas encore. */
	void noteParent(UUID uuid, String name, String profession, boolean baby, String village) {
		Kin k = entries.computeIfAbsent(uuid.toString(), key -> new Kin());
		k.name = name;
		k.profession = profession;
		k.baby = baby;
		if (village != null) {
			k.village = village;
		}
		k.fate = Kin.Fate.ALIVE;
		dirty = true;
	}

	void birth(UUID child, List<UUID> parents, String name, String village, long day) {
		Kin k = entries.computeIfAbsent(child.toString(), key -> new Kin());
		k.name = name;
		k.baby = true;
		k.born = day;
		k.village = village;
		k.parents = parents.stream().map(UUID::toString).toList();
		dirty = true;
	}

	/** Villageois vu vivant : met à jour son entrée s'il en a une (nom, métier, village). */
	void update(VillagerRecord r, String village) {
		Kin k = entries.get(r.uuid);
		if (k == null) {
			return;
		}
		String name = r.displayName();
		if (!name.equals(k.name) || !r.profession.equals(k.profession) || r.baby != k.baby
				|| !village.equals(k.village) || k.fate != Kin.Fate.ALIVE) {
			k.name = name;
			k.profession = r.profession;
			k.baby = r.baby;
			k.village = village;
			k.fate = Kin.Fate.ALIVE;
			dirty = true;
		}
	}

	void fate(UUID uuid, Kin.Fate fate, long day) {
		Kin k = entries.get(uuid.toString());
		if (k != null && k.fate != fate) {
			k.fate = fate;
			k.fateDay = day;
			dirty = true;
		}
	}

	/**
	 * Entrées utiles au tableau d'un village : ses habitants et les anciens habitants, plus leurs parents et
	 * enfants sur {@link #VIEW_DEPTH} générations (même s'ils vivent ailleurs).
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
				if (keep.contains(uuid)) {
					k.parents.stream().filter(entries::containsKey).forEach(more::add);
				} else if (k.parents.stream().anyMatch(keep::contains)) {
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

	/**
	 * Naissances enregistrées avant l'état civil : seuls les noms des parents étaient notés (« Anne &amp; Côme »).
	 * On retrouve chaque parent quand un seul habitant porte ce nom.
	 */
	void migrate(Collection<Village> villages) {
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
					noteParent(UUID.fromString(p.uuid), p.displayName(), p.profession, p.baby, villageOf.get(p.uuid));
				}
				birth(UUID.fromString(r.uuid), found.stream().map(p -> UUID.fromString(p.uuid)).toList(),
						r.displayName(), v.id, r.firstSeenDay);
				Kin k = entries.get(r.uuid);
				k.profession = r.profession;
				k.baby = r.baby;
			}
		}
	}
}
