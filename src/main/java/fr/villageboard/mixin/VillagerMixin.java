package fr.villageboard.mixin;

import fr.villageboard.village.Professions;
import fr.villageboard.village.VillageManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Villager.class)
public abstract class VillagerMixin {

	/** Posé quand un changement de métier est bloqué (villageois verrouillé ou lié à un poste) : le rafraîchissement du cerveau qui suit est inutile. */
	@Unique
	private boolean villageboard$skipBrainRefresh;

	/**
	 * Changement de métier par le jeu (prise ou perte de poste). Ignoré tant que l'entité n'a pas encore
	 * tické (chargement depuis le disque, guérison, apparition) pour ne pas confondre lecture et changement.
	 */
	@Inject(method = "setVillagerData", at = @At("HEAD"), cancellable = true)
	private void villageboard$onSetVillagerData(VillagerData data, CallbackInfo ci) {
		Villager self = (Villager) (Object) this;
		if (!(self.level() instanceof ServerLevel) || self.tickCount == 0 || VillageManager.get() == null) {
			return;
		}
		String before = Professions.key(self);
		String after = data.profession().unwrapKey().map(k -> k.identifier().toString()).orElse(Professions.NONE);
		if (before.equals(after)) {
			return;
		}
		if (VillageManager.get().isFrozen(self)) {
			villageboard$skipBrainRefresh = true;
			ci.cancel();
			return;
		}
		VillageManager.get().onCareerChange(self, before, after);
	}

	@Inject(method = "refreshBrain", at = @At("HEAD"), cancellable = true)
	private void villageboard$onRefreshBrain(ServerLevel level, CallbackInfo ci) {
		if (villageboard$skipBrainRefresh) {
			villageboard$skipBrainRefresh = false;
			ci.cancel();
		}
	}

	/** Naissance : on retient les parents jusqu'à l'apparition du bébé dans le monde. */
	@Inject(method = "getBreedOffspring(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/AgeableMob;)Lnet/minecraft/world/entity/npc/villager/Villager;",
			at = @At("RETURN"))
	private void villageboard$onBreed(ServerLevel level, AgeableMob partner, CallbackInfoReturnable<Villager> cir) {
		Villager child = cir.getReturnValue();
		if (child != null && partner instanceof Villager other && VillageManager.get() != null) {
			VillageManager.get().onBreed(child, (Villager) (Object) this, other);
		}
	}
}
