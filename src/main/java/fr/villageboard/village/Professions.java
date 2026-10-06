package fr.villageboard.village;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;

import java.util.Optional;

public final class Professions {

	public static final String NONE = "minecraft:none";
	public static final String NITWIT = "minecraft:nitwit";

	private Professions() {
	}

	public static String key(Villager villager) {
		return key(villager.getVillagerData().profession());
	}

	public static String key(Holder<VillagerProfession> profession) {
		return profession.unwrapKey().map(k -> k.identifier().toString()).orElse(NONE);
	}

	/** Le métier que donne ce type de poste de travail (pupitre → bibliothécaire…), s'il y en a un. */
	static Optional<Holder<VillagerProfession>> forPoi(ServerLevel level, Holder<PoiType> poi) {
		if (!VillagerProfession.ALL_ACQUIRABLE_JOBS.test(poi)) {
			return Optional.empty();
		}
		return level.registryAccess().lookupOrThrow(Registries.VILLAGER_PROFESSION).listElements()
				.filter(p -> !p.is(VillagerProfession.NONE) && p.value().heldJobSite().test(poi))
				.<Holder<VillagerProfession>>map(p -> p)
				.findFirst();
	}
}
