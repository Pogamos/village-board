package fr.villageboard.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import fr.villageboard.client.TradeCard;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Décale la fenêtre de commerce vers la droite quand une fiche du villageois est affichée, pour lui laisser la place
 * à gauche (voir {@link TradeCard#shift}). MerchantScreen recalcule sa position avec {@code (width - imageWidth) / 2}
 * dans plusieurs méthodes, et les cases d'inventaire utilisent {@code leftPos} : on décale les deux de la même façon.
 */
@Mixin(MerchantScreen.class)
public abstract class MerchantScreenMixin {

	@ModifyExpressionValue(method = {"init", "extractBackground", "extractContents", "mouseClicked"},
			at = @At(value = "FIELD", target = "Lnet/minecraft/client/gui/screens/inventory/MerchantScreen;width:I",
					opcode = Opcodes.GETFIELD))
	private int villageboard$shiftedWidth(int width) {
		return width + 2 * TradeCard.shift(width);
	}

	@Inject(method = "init", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;init()V", shift = At.Shift.AFTER))
	private void villageboard$shiftLeftPos(CallbackInfo ci) {
		AbstractContainerScreenAccessor self = (AbstractContainerScreenAccessor) this;
		self.villageboard$setLeftPos(self.villageboard$getLeftPos() + TradeCard.shift(((Screen) (Object) this).width));
	}
}
