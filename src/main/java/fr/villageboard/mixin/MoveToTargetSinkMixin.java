package fr.villageboard.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import fr.villageboard.village.VillageManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.MoveToTargetSink;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Chemins du village. Quand un villageois calcule son chemin vers une position lointaine (lit, poste, cloche…), on lui
 * fait plutôt viser l'étape suivante sur le réseau de son village : un chemin court, que Minecraft trouve sans peine.
 * Arrivé à l'étape, le villageois recalcule son chemin, ce qui lui donne l'étape d'après. Sa cible ne change pas :
 * il s'arrête dès qu'il l'atteint, et un chemin trouvé jusqu'à l'étape compte comme un progrès (il n'oublie pas son lit
 * en route). Si l'étape est inaccessible, Minecraft cherche le chemin complet comme d'habitude. Si le réseau ne connaît
 * pas encore de chemin et que Minecraft échoue, un calcul élargi est tenté une fois (exploration, voir Roads).
 */
@Mixin(MoveToTargetSink.class)
public abstract class MoveToTargetSinkMixin {

	@WrapOperation(method = "tryComputePath", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;createPath(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/pathfinder/Path;"))
	private Path villageboard$followVillagePaths(PathNavigation navigation, BlockPos target, int reachRange, Operation<Path> original,
			@Local(argsOnly = true) Mob body, @Local(argsOnly = true) WalkTarget walkTarget) {
		VillageManager manager = VillageManager.get();
		if (manager != null && body instanceof Villager villager && walkTarget.getTarget() instanceof BlockPosTracker) {
			BlockPos step = manager.roadStep(villager, target);
			if (step != null) {
				// À un bloc près : Minecraft remonte d'un cran une cible posée sur une marche, une dalle ou un chemin de terre.
				Path path = original.call(navigation, step, Math.max(reachRange, 1));
				if (path != null && path.canReach()) {
					return path;
				}
				manager.roadFailed(villager);
			} else {
				Path explored = manager.explorePath(villager, target, reachRange);
				if (explored != null) {
					return explored;
				}
			}
		}
		return original.call(navigation, target, reachRange);
	}
}
