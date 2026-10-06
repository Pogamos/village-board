package fr.villageboard.village;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Lits du territoire, d'après les points d'intérêt « maison » de Minecraft (un par lit, sur la tête du lit).
 * Seuls les chunks chargés sont relus ; les autres gardent leur dernier état connu (cache par chunk dans le village).
 * Un village sans lit libre ne peut plus avoir de naissances.
 */
final class Housing {

	record Bed(BlockPos pos, boolean occupied) {
	}

	/** -1 : inconnu, 0 : aucun lit libre, 1 : au moins un lit libre. */
	static final int UNKNOWN = -1;
	static final int FULL = 0;
	static final int AVAILABLE = 1;
	/** Nombre de recensements consécutifs avant d'annoncer un changement (évite les va-et-vient). */
	private static final int CONFIRMATIONS = 2;

	private Housing() {
	}

	static void scan(ServerLevel level, Village village, int defaultRadius) {
		BlockPos board = village.boardPos();
		int minX = board.getX() - defaultRadius;
		int maxX = board.getX() + defaultRadius;
		int minZ = board.getZ() - defaultRadius;
		int maxZ = board.getZ() + defaultRadius;
		if (village.polygon().size() >= 3) {
			minX = maxX = board.getX();
			minZ = maxZ = board.getZ();
			for (BlockPos p : village.polygon()) {
				minX = Math.min(minX, p.getX());
				maxX = Math.max(maxX, p.getX());
				minZ = Math.min(minZ, p.getZ());
				maxZ = Math.max(maxZ, p.getZ());
			}
		}
		int cx1 = minX >> 4;
		int cx2 = maxX >> 4;
		int cz1 = minZ >> 4;
		int cz2 = maxZ >> 4;
		PoiManager poi = level.getPoiManager();
		for (int cx = cx1; cx <= cx2; cx++) {
			for (int cz = cz1; cz <= cz2; cz++) {
				if (!level.hasChunk(cx, cz)) {
					continue;
				}
				List<Bed> beds = poi.getInChunk(type -> type.is(PoiTypes.HOME), new ChunkPos(cx, cz), PoiManager.Occupancy.ANY)
						.filter(r -> village.contains(village.dimension, r.getPos().getX() + 0.5, r.getPos().getZ() + 0.5, defaultRadius))
						.map(r -> new Bed(r.getPos(), !r.hasSpace()))
						.toList();
				village.bedCache.put(chunkKey(cx, cz), beds);
			}
		}
		final int fx1 = cx1, fx2 = cx2, fz1 = cz1, fz2 = cz2;
		village.bedCache.keySet().removeIf(key -> {
			int cx = (int) (key >> 32);
			int cz = (int) (long) key;
			return cx < fx1 || cx > fx2 || cz < fz1 || cz > fz2;
		});
	}

	static List<Bed> beds(Village village) {
		List<Bed> all = new ArrayList<>();
		village.bedCache.values().forEach(all::addAll);
		return all;
	}

	/**
	 * Met à jour l'état « lits libres / aucun lit libre » et renvoie le nouvel état s'il vient de changer
	 * (confirmé sur plusieurs recensements), sinon null. Le premier état connu est adopté sans annonce.
	 */
	static Integer updateState(Village village, int freeBeds) {
		int state = freeBeds > 0 ? AVAILABLE : FULL;
		if (village.housingState == UNKNOWN) {
			village.housingState = state;
			village.dirty = true;
			return null;
		}
		if (state == village.housingState) {
			village.pendingHousingState = UNKNOWN;
			village.pendingHousingCount = 0;
			return null;
		}
		if (village.pendingHousingState == state) {
			village.pendingHousingCount++;
		} else {
			village.pendingHousingState = state;
			village.pendingHousingCount = 1;
		}
		if (village.pendingHousingCount < CONFIRMATIONS) {
			return null;
		}
		village.housingState = state;
		village.pendingHousingState = UNKNOWN;
		village.pendingHousingCount = 0;
		village.dirty = true;
		return state;
	}

	private static long chunkKey(int cx, int cz) {
		return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
	}
}
