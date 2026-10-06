package fr.villageboard.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.util.function.Consumer;

/**
 * Contrat de travail. 1) clic droit sur un villageois : il est inscrit sur le contrat ;
 * 2) clic droit sur un poste de travail : il y est affecté pour de bon (voir VillageManager#assign).
 * Accroupi + clic droit dans le vide : efface le contrat. Les clics sur villageois et blocs sont
 * interceptés dans ServerEvents, avant l'ouverture du commerce ou de l'interface du bloc.
 */
public class WorkContractItem extends Item {

	public WorkContractItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (player.isShiftKeyDown() && stack.get(ModItems.CONTRACT_TARGET) != null) {
			stack.remove(ModItems.CONTRACT_TARGET);
			if (!level.isClientSide()) {
				player.sendOverlayMessage(Component.translatable("villageboard.contract.cleared"));
			}
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	@Override
	public boolean isFoil(ItemStack stack) {
		return stack.get(ModItems.CONTRACT_TARGET) != null;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
								Consumer<Component> tooltip, TooltipFlag flag) {
		ContractTarget target = stack.get(ModItems.CONTRACT_TARGET);
		if (target == null) {
			tooltip.accept(Component.translatable("villageboard.contract.empty").withStyle(ChatFormatting.GRAY));
			return;
		}
		Component who = target.name().isEmpty()
				? Component.translatable("villageboard.someone.cap")
				: Component.literal(target.name());
		tooltip.accept(Component.translatable("villageboard.contract.for", who).withStyle(ChatFormatting.GOLD));
		tooltip.accept(Component.translatable("villageboard.contract.next").withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("villageboard.contract.clear").withStyle(ChatFormatting.DARK_GRAY));
	}
}
