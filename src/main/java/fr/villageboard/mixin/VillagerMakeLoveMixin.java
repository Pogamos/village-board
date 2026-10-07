package fr.villageboard.mixin;

import fr.villageboard.village.VillageManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.VillagerMakeLove;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Lit du bébé à la naissance. Minecraft ne cherche un lit libre qu'à 48 blocs de la mère : sans quoi la naissance
 * échoue (éclairs au-dessus des parents). Dans ce cas, on cherche un lit libre plus loin sur le territoire du village,
 * que la mère peut atteindre à pied (voir {@link VillageManager#takeVillageBed}).
 */
@Mixin(VillagerMakeLove.class)
public abstract class VillagerMakeLoveMixin {

	@Inject(method = "takeVacantBed", at = @At("RETURN"), cancellable = true)
	private void villageboard$takeVillageBed(ServerLevel level, Villager mother, CallbackInfoReturnable<Optional<BlockPos>> cir) {
		if (cir.getReturnValue().isEmpty() && VillageManager.get() != null) {
			VillageManager.get().takeVillageBed(level, mother).ifPresent(bed -> cir.setReturnValue(Optional.of(bed)));
		}
	}
}
