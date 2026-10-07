package fr.villageboard.village;

import fr.villageboard.VillageBoard;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.pathfinder.Path;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Lits lointains. Un villageois ne voit les lits qu'à 48 blocs, que ce soit pour se trouver un lit ou pour celui de son
 * bébé à la naissance (au-delà, la naissance échoue : éclairs au-dessus des parents). Dans un village plus grand, on lui
 * fait aussi voir les lits libres du reste du territoire, jusqu'à {@link #MAX_DISTANCE} blocs (au-delà de 150, il
 * oublierait son lit), à condition qu'un chemin y mène : un lit inaccessible le reste.
 * <p>
 * Le calcul de chemin d'un villageois est lui-même limité par sa portée de suivi (48 blocs) : on l'élargit le temps de la
 * vérification, avec un modificateur temporaire de {@code FOLLOW_RANGE} (Minecraft agrandit alors la recherche de chemin).
 */
final class FarBeds {

	/** Portée de Minecraft : les lits plus proches sont déjà vus par le villageois lui-même. */
	static final int VANILLA_RANGE = 48;
	static final int MAX_DISTANCE = 140;
	/** Chemins calculés au plus par recherche (un calcul lointain coûte plus cher qu'un calcul vanilla). */
	private static final int MAX_TRIES = 3;
	/** Précision demandée pour atteindre un lit, comme {@code PoiType.validRange()} des lits. */
	private static final int BED_ACCURACY = 1;
	private static final Identifier PATH_RANGE = VillageBoard.id("far_bed_path");

	private FarBeds() {
	}

	/** Un lit libre du village que ce villageois ne voit pas lui-même (plus de 48 blocs, moins de 140) ? */
	static boolean anyFor(Villager villager, Village village) {
		BlockPos from = villager.blockPosition();
		return Facilities.beds(village).stream().anyMatch(b -> !b.occupied() && inFarRange(b.pos(), from));
	}

	/**
	 * Réserve pour ce villageois le lit libre le plus proche du village situé hors de sa portée naturelle, parmi ceux
	 * qu'il peut atteindre à pied. Vide s'il n'y en a pas.
	 */
	static Optional<BlockPos> take(ServerLevel level, Villager villager, Village village) {
		BlockPos from = villager.blockPosition();
		List<BlockPos> candidates = Facilities.beds(village).stream()
				.filter(b -> !b.occupied() && inFarRange(b.pos(), from) && level.isLoaded(b.pos()))
				.map(Facilities.Bed::pos)
				.sorted(Comparator.comparingDouble(p -> p.distSqr(from)))
				.limit(MAX_TRIES)
				.toList();
		for (BlockPos bed : candidates) {
			if (reachable(villager, bed)) {
				// Le recensement date de quelques secondes : take() revérifie que le lit existe et qu'il est toujours libre.
				Optional<BlockPos> taken = level.getPoiManager().take(t -> t.is(PoiTypes.HOME), (t, p) -> p.equals(bed), bed, 1);
				if (taken.isPresent()) {
					return taken;
				}
			}
		}
		return Optional.empty();
	}

	private static boolean inFarRange(BlockPos bed, BlockPos from) {
		double d = bed.distSqr(from);
		return d > VANILLA_RANGE * VANILLA_RANGE && d <= MAX_DISTANCE * MAX_DISTANCE;
	}

	/** Un chemin mène-t-il du villageois jusqu'au lit ? Calcul de chemin élargi à la distance du lit. */
	private static boolean reachable(Villager villager, BlockPos bed) {
		AttributeInstance range = villager.getAttribute(Attributes.FOLLOW_RANGE);
		if (range == null) {
			return false;
		}
		double needed = Math.sqrt(bed.distSqr(villager.blockPosition())) + 16;
		double extra = needed - range.getValue();
		if (extra > 0) {
			range.addTransientModifier(new AttributeModifier(PATH_RANGE, extra, AttributeModifier.Operation.ADD_VALUE));
		}
		try {
			Path path = villager.getNavigation().createPath(bed, BED_ACCURACY);
			return path != null && path.canReach();
		} finally {
			if (extra > 0) {
				range.removeModifier(PATH_RANGE);
			}
		}
	}
}
