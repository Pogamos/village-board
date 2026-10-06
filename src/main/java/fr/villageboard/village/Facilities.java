package fr.villageboard.village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Équipements du territoire, d'après les points d'intérêt de Minecraft : lits (POI « maison », sur la tête du lit),
 * postes de travail (avec leur métier) et cloches (POI « lieu de rassemblement »).
 * Seuls les chunks chargés sont relus ; les autres gardent leur dernier état connu (cache par chunk dans le village).
 */
final class Facilities {

	record Bed(BlockPos pos, boolean occupied) {
	}

	/** Poste de travail ; {@code profession} = identifiant complet du métier qu'il donne. */
	record Workstation(BlockPos pos, String profession, boolean occupied) {
	}

	/** Ce qu'on a trouvé dans un chunk. */
	record ChunkFacilities(List<Bed> beds, List<Workstation> workstations, int bells) {
	}

	/** État du logement : -1 inconnu, 0 aucun lit libre, 1 au moins un lit libre. */
	static final int UNKNOWN = -1;
	static final int FULL = 0;
	static final int AVAILABLE = 1;
	/** Nombre de recensements consécutifs avant d'annoncer un changement (évite les va-et-vient). */
	private static final int CONFIRMATIONS = 2;

	private Facilities() {
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
				List<Bed> beds = new ArrayList<>();
				List<Workstation> workstations = new ArrayList<>();
				int bells = 0;
				List<PoiRecord> records = poi.getInChunk(Facilities::isFacility, new ChunkPos(cx, cz), PoiManager.Occupancy.ANY).toList();
				for (PoiRecord r : records) {
					BlockPos pos = r.getPos();
					if (!village.contains(village.dimension, pos.getX() + 0.5, pos.getZ() + 0.5, defaultRadius)) {
						continue;
					}
					Holder<PoiType> type = r.getPoiType();
					if (type.is(PoiTypes.HOME)) {
						beds.add(new Bed(pos, !r.hasSpace()));
					} else if (type.is(PoiTypes.MEETING)) {
						bells++;
					} else {
						Professions.forPoi(level, type).ifPresent(p ->
								workstations.add(new Workstation(pos, Professions.key(p), !r.hasSpace())));
					}
				}
				village.facilityCache.put(chunkKey(cx, cz), new ChunkFacilities(beds, workstations, bells));
			}
		}
		final int fx1 = cx1, fx2 = cx2, fz1 = cz1, fz2 = cz2;
		village.facilityCache.keySet().removeIf(key -> {
			int cx = (int) (key >> 32);
			int cz = (int) (long) key;
			return cx < fx1 || cx > fx2 || cz < fz1 || cz > fz2;
		});
	}

	private static boolean isFacility(Holder<PoiType> type) {
		return type.is(PoiTypes.HOME) || type.is(PoiTypes.MEETING) || VillagerProfession.ALL_ACQUIRABLE_JOBS.test(type);
	}

	static List<Bed> beds(Village village) {
		List<Bed> all = new ArrayList<>();
		village.facilityCache.values().forEach(c -> all.addAll(c.beds()));
		return all;
	}

	static List<Workstation> workstations(Village village) {
		List<Workstation> all = new ArrayList<>();
		village.facilityCache.values().forEach(c -> all.addAll(c.workstations()));
		return all;
	}

	static int bells(Village village) {
		return village.facilityCache.values().stream().mapToInt(ChunkFacilities::bells).sum();
	}

	/**
	 * Met à jour l'état « lits libres / aucun lit libre » et renvoie le nouvel état s'il vient de changer
	 * (confirmé sur plusieurs recensements), sinon null. Le premier état connu est adopté sans annonce.
	 */
	static Integer updateHousingState(Village village, int freeBeds) {
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
