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
 * Contrat de travail, bail de logement ou acte de mariage. 1) clic droit sur un villageois : il est inscrit dessus ;
 * 2) clic droit sur un poste de travail (contrat), un lit (bail) ou un autre villageois (mariage) : ils sont liés
 * pour de bon (voir village.Assignments et village.Marriages). Accroupi + clic droit dans le vide : efface le nom inscrit.
 * Les clics sur villageois et blocs sont interceptés dans ServerEvents, avant le commerce ou l'interface du bloc.
 */
public class ContractItem extends Item {

	private final ContractKind kind;

	public ContractItem(Properties properties, ContractKind kind) {
		super(properties);
		this.kind = kind;
	}

	public ContractKind kind() {
		return kind;
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
			tooltip.accept(Component.translatable(kind.keyPrefix + "empty").withStyle(ChatFormatting.GRAY));
			return;
		}
		Component who = target.name().isEmpty()
				? Component.translatable("villageboard.someone.cap")
				: Component.literal(target.name());
		tooltip.accept(Component.translatable(kind.keyPrefix + "for", who).withStyle(ChatFormatting.GOLD));
		tooltip.accept(Component.translatable(kind.keyPrefix + "next").withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("villageboard.contract.clear").withStyle(ChatFormatting.DARK_GRAY));
	}
}
