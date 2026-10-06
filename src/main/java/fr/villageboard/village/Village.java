package fr.villageboard.village;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Un village : son tableau, ses bornes, son registre d'habitants et sa gazette. Sérialisé en JSON. */
public final class Village {

	public String id;
	public String name;
	public String dimension;
	public long board;
	public String founder;
	public String founderName;
	public long foundedAt;
	public List<Long> bornes = new ArrayList<>();
	/** Clé : UUID du villageois. */
	public Map<String, VillagerRecord> villagers = new LinkedHashMap<>();
	/** La plus récente en premier. */
	public List<NewsEntry> news = new ArrayList<>();

	transient boolean dirty;
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
