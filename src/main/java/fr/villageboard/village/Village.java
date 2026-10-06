package fr.villageboard.village;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Un village : son tableau, ses bornes, son registre d'habitants et sa gazette. Sérialisé en JSON. */
public final class Village {

	public String id;
	public String name;
	public String dimension;
	/** Position du tableau de la mairie ; s'il a été retiré ({@link #boardMissing}), sa dernière position. */
	public long board;
	/** Tableau retiré : le village et ses bornes sont conservés jusqu'à ce qu'un tableau soit reposé sur le territoire. */
	public boolean boardMissing;
	public String founder;
	public String founderName;
	public long foundedAt;
	public List<Long> bornes = new ArrayList<>();
	/** Clé : UUID du villageois. */
	public Map<String, VillagerRecord> villagers = new LinkedHashMap<>();
	/** La plus récente en premier. */
	public List<NewsEntry> news = new ArrayList<>();
	/** Dernier état annoncé du logement (voir {@link Facilities}). */
	public int housingState = Facilities.UNKNOWN;

	transient boolean dirty;
	/** Lits, postes et cloches connus, par chunk (clé : x << 32 | z). Recalculés au recensement, non sauvegardés. */
	transient Map<Long, Facilities.ChunkFacilities> facilityCache = new HashMap<>();
	/** Golems de fer vus sur le territoire au dernier recensement. */
	transient int golems;
	transient int pendingHousingState = Facilities.UNKNOWN;
	transient int pendingHousingCount;
	private transient List<BlockPos> polygon;

	public BlockPos boardPos() {
		return BlockPos.of(board);
	}

	public List<BlockPos> polygon() {
		if (polygon == null) {
			polygon = Territory.order(bornes.stream().map(BlockPos::of).toList());
		}
		return polygon;
	}

	void bornesChanged() {
		polygon = null;
		dirty = true;
	}

	public boolean contains(String dim, double x, double z, int defaultRadius) {
		return dimension.equals(dim) && Territory.contains(polygon(), boardPos(), defaultRadius, x, z);
	}
}
