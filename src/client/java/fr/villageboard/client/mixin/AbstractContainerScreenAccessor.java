package fr.villageboard.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accès à la position de la fenêtre d'un écran de conteneur (pour décaler le commerce, voir {@link MerchantScreenMixin}). */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {

	@Accessor("leftPos")
	int villageboard$getLeftPos();

	@Accessor("leftPos")
	void villageboard$setLeftPos(int leftPos);
}
