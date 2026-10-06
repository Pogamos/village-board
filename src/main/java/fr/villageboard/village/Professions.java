package fr.villageboard.village;

import net.minecraft.world.entity.npc.villager.Villager;

public final class Professions {

	public static final String NONE = "minecraft:none";
	public static final String NITWIT = "minecraft:nitwit";

	private Professions() {
	}

	public static String key(Villager villager) {
		return villager.getVillagerData().profession().unwrapKey()
				.map(k -> k.identifier().toString())
				.orElse(NONE);
	}
}
