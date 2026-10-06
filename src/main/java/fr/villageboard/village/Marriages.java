package fr.villageboard.village;

import fr.villageboard.item.ContractKind;
import fr.villageboard.item.ContractTarget;
import fr.villageboard.item.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * Acte de mariage : clic droit sur un villageois (inscrit sur l'acte), puis sur son futur conjoint, qui doit se trouver
 * à moins de {@link #MAX_DISTANCE} blocs. Les deux doivent être adultes, célibataires et sans lien de parenté proche.
 * Les mariages d'office (au premier enfant) sont faits par {@link VillageManager#onBreed}.
 */
final class Marriages {

	private static final double MAX_DISTANCE = 16;
	private static final String KEYS = ContractKind.MARRIAGE.keyPrefix;

	private final VillageManager manager;

	Marriages(VillageManager manager) {
		this.manager = manager;
	}

	void useOnVillager(ServerPlayer player, ItemStack stack, Villager villager) {
		if (villager.isBaby()) {
			player.sendOverlayMessage(Component.translatable(KEYS + "child"));
			return;
		}
		manager.observe(villager, true);
		Village village = manager.villageOf(villager.getUUID());
		if (village == null) {
			player.sendOverlayMessage(Component.translatable("villageboard.contract.no_village"));
			return;
		}
		if (!manager.canManage(player, village)) {
			player.sendOverlayMessage(Component.translatable("villageboard.msg.no_permission"));
			return;
		}
		Genealogy genealogy = manager.genealogy();
		String name = manager.displayName(villager);
		if (alreadyMarried(player, genealogy, villager.getUUID(), name)) {
			return;
		}
		ServerLevel level = player.level();
		ContractTarget target = stack.get(ModItems.CONTRACT_TARGET);
		if (target == null || target.villager().equals(villager.getUUID())) {
			stack.set(ModItems.CONTRACT_TARGET, new ContractTarget(villager.getUUID(), name, Professions.key(villager)));
			level.playSound(null, villager.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1f, 1f);
			player.sendOverlayMessage(Component.translatable(KEYS + "selected", VillageManager.whoCap(name)));
			return;
		}

		Villager first = level.getEntity(target.villager()) instanceof Villager v && v.isAlive() ? v : null;
		if (first == null || first.distanceTo(villager) > MAX_DISTANCE) {
			player.sendOverlayMessage(Component.translatable(KEYS + "absent", VillageManager.whoCap(target.name()), (int) MAX_DISTANCE));
			return;
		}
		String firstName = manager.displayName(first);
		if (alreadyMarried(player, genealogy, first.getUUID(), firstName)) {
			return;
		}
		if (genealogy.related(first.getUUID(), villager.getUUID())) {
			player.sendOverlayMessage(Component.translatable(KEYS + "related",
					VillageManager.whoCap(firstName), VillageManager.who(name)));
			return;
		}

		for (Villager v : new Villager[]{first, villager}) {
			Village home = manager.villageOf(v.getUUID());
			genealogy.touch(v.getUUID(), manager.displayName(v), Professions.key(v), Professions.type(v), false,
					home == null ? null : home.id);
			level.broadcastEntityEvent(v, EntityEvent.LOVE_HEARTS);
		}
		genealogy.marry(first.getUUID(), villager.getUUID(), manager.currentDay());
		manager.news(village, NewsType.MARRIED, firstName, name, "ceremony");
		stack.remove(ModItems.CONTRACT_TARGET);
		level.playSound(null, villager.blockPosition(), SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 1f, 1f);
		player.sendSystemMessage(Component.translatable(KEYS + "done", VillageManager.whoCap(firstName), VillageManager.who(name)));
	}

	private boolean alreadyMarried(ServerPlayer player, Genealogy genealogy, UUID villager, String name) {
		UUID spouse = genealogy.spouse(villager);
		if (spouse == null) {
			return false;
		}
		Kin k = genealogy.get(spouse);
		player.sendOverlayMessage(Component.translatable(KEYS + "already",
				VillageManager.whoCap(name), VillageManager.who(k == null ? "" : k.name)));
		return true;
	}
}
