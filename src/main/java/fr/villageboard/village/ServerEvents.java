package fr.villageboard.village;

import fr.villageboard.block.ModBlocks;
import fr.villageboard.item.ContractItem;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.npc.villager.Villager;

/** Branche le gestionnaire de villages sur les événements du serveur. */
public final class ServerEvents {

	private ServerEvents() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(VillageManager::start);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> VillageManager.stop());
		ServerTickEvents.END_SERVER_TICK.register(server -> ifRunning(VillageManager::tick));

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				ifRunning(m -> m.sendBorders(handler.getPlayer())));

		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof Villager villager) {
				ifRunning(m -> m.onEntityLoad(villager));
			}
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof Villager villager) {
				ifRunning(m -> m.onEntityUnload(villager));
			}
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof Villager villager) {
				ifRunning(m -> m.onDeath(villager, source));
			}
		});
		ServerLivingEntityEvents.MOB_CONVERSION.register((from, to, params) -> {
			if (from instanceof Villager villager) {
				ifRunning(m -> m.onConvertedFrom(villager, to.getType()));
			} else if (from instanceof ZombieVillager && to instanceof Villager cured) {
				ifRunning(m -> m.onCured(cured));
			}
		});

		// Contrat, bail et acte de mariage : interceptés avant le commerce avec le villageois / l'interface du bloc.
		// Les mains vides (ou un objet ordinaire) : le villageois dit d'abord une réplique (voir VillageManager.talk).
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!(entity instanceof Villager villager)) {
				return InteractionResult.PASS;
			}
			ItemStack stack = player.getItemInHand(hand);
			if (stack.getItem() instanceof ContractItem contract) {
				if (player instanceof ServerPlayer serverPlayer && VillageManager.get() != null) {
					VillageManager.get().useContractOnVillager(serverPlayer, stack, villager, contract.kind());
				}
				return InteractionResult.SUCCESS;
			}
			// Accroupi, ou avec une étiquette, une laisse, un œuf d'apparition : comportement vanilla, sans réplique.
			if (hand != InteractionHand.MAIN_HAND || player.isShiftKeyDown() || player.isSpectator()
					|| stack.is(Items.NAME_TAG) || stack.is(Items.LEAD) || stack.getItem() instanceof SpawnEggItem) {
				return InteractionResult.PASS;
			}
			if (player instanceof ServerPlayer serverPlayer && VillageManager.get() != null
					&& VillageManager.get().talk(serverPlayer, villager)) {
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!(player instanceof ServerPlayer serverPlayer) || !(player.getItemInHand(hand).getItem() instanceof ContractItem contract)
					|| VillageManager.get() == null) {
				return InteractionResult.PASS;
			}
			return VillageManager.get().useContractOnBlock(serverPlayer, player.getItemInHand(hand), hit.getBlockPos(), contract.kind());
		});

		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
			if (!state.is(ModBlocks.TOWN_BOARD) && !state.is(ModBlocks.BOUNDARY_STONE)) {
				return true;
			}
			VillageManager m = VillageManager.get();
			return m == null || m.allowBreak(level, player, pos);
		});
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (state.is(ModBlocks.TOWN_BOARD) || state.is(ModBlocks.BOUNDARY_STONE)) {
				ifRunning(m -> m.afterBreak(level, player, pos));
			}
		});
	}

	private static void ifRunning(java.util.function.Consumer<VillageManager> action) {
		VillageManager m = VillageManager.get();
		if (m != null) {
			action.accept(m);
		}
	}
}
