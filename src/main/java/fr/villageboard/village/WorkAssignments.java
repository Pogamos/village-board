package fr.villageboard.village;

import fr.villageboard.item.ContractTarget;
import fr.villageboard.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Postes attitrés : un contrat de travail lie un villageois à un poste de travail précis.
 * Un villageois lié garde ce poste (et le métier qui va avec) : s'il le perd de vue, il y est
 * réaffecté ; si le poste est détruit, le lien est rompu et la gazette l'annonce.
 */
final class WorkAssignments {

	private static final double MAX_ASSIGN_DISTANCE = 48;
	private static final double HOLDER_SEARCH_RADIUS = 64;

	private final VillageManager manager;
	private final MinecraftServer server;

	WorkAssignments(VillageManager manager, MinecraftServer server) {
		this.manager = manager;
		this.server = server;
	}

	static boolean isBound(Villager villager) {
		return villager.hasAttached(Attachments.BOUND_SITE);
	}

	// ------------------------------------------------------------------ contrat

	/** Clic droit sur un villageois : il est inscrit sur le contrat. */
	void selectVillager(ServerPlayer player, ItemStack stack, Villager villager) {
		String profession = Professions.key(villager);
		if (villager.isBaby()) {
			player.sendOverlayMessage(Component.translatable("villageboard.contract.child"));
			return;
		}
		if (profession.equals(Professions.NITWIT)) {
			player.sendOverlayMessage(Component.translatable("villageboard.contract.nitwit"));
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
		String name = manager.displayName(villager);
		stack.set(ModItems.CONTRACT_TARGET, new ContractTarget(villager.getUUID(), name, profession));
		player.level().playSound(null, villager.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1f, 1f);
		player.sendOverlayMessage(Component.translatable("villageboard.contract.selected", VillageManager.whoCap(name)));
	}

	/**
	 * Clic droit sur un bloc. Si c'est un poste de travail : affecte le villageois du contrat,
	 * ou indique qui l'occupe si le contrat est vierge. Sinon PASS (le bloc réagit normalement).
	 */
	InteractionResult useOnBlock(ServerPlayer player, ItemStack stack, BlockPos pos) {
		ServerLevel level = player.level();
		Optional<Holder<PoiType>> poi = level.getPoiManager().getType(pos);
		Optional<Holder<VillagerProfession>> profession = poi.flatMap(p -> professionFor(level, p));
		if (profession.isEmpty()) {
			return InteractionResult.PASS;
		}
		ContractTarget target = stack.get(ModItems.CONTRACT_TARGET);
		if (target == null) {
			Villager holder = holderOf(level, pos);
			player.sendOverlayMessage(holder == null
					? Component.translatable("villageboard.contract.site_free", profession.get().value().name())
					: Component.translatable("villageboard.contract.site_taken", VillageManager.whoCap(manager.displayName(holder))));
			return InteractionResult.SUCCESS;
		}

		Villager villager = level.getEntity(target.villager()) instanceof Villager v && v.isAlive() ? v : null;
		if (villager == null) {
			player.sendOverlayMessage(Component.translatable("villageboard.contract.not_found"));
			return InteractionResult.SUCCESS;
		}
		Village village = manager.villageOf(villager.getUUID());
		if (village == null || !manager.canManage(player, village)) {
			player.sendOverlayMessage(Component.translatable(village == null ? "villageboard.contract.no_village" : "villageboard.msg.no_permission"));
			return InteractionResult.SUCCESS;
		}
		if (villager.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_ASSIGN_DISTANCE * MAX_ASSIGN_DISTANCE) {
			player.sendOverlayMessage(Component.translatable("villageboard.contract.too_far", (int) MAX_ASSIGN_DISTANCE));
			return InteractionResult.SUCCESS;
		}
		String current = Professions.key(villager);
		boolean changesJob = !villager.getVillagerData().profession().is(profession.get());
		if (changesJob && !current.equals(Professions.NONE)
				&& (villager.getVillagerXp() > 0 || villager.getVillagerData().level() > 1)) {
			player.sendOverlayMessage(Component.translatable("villageboard.contract.experienced",
					VillageManager.whoCap(manager.displayName(villager)), villager.getVillagerData().profession().value().name()));
			return InteractionResult.SUCCESS;
		}

		Villager evicted = bind(villager, level, pos, profession.get());
		if (evicted != null) {
			player.sendSystemMessage(Component.translatable("villageboard.contract.evicted", VillageManager.whoCap(manager.displayName(evicted))));
		}
		stack.remove(ModItems.CONTRACT_TARGET);
		String name = manager.displayName(villager);
		manager.news(village, NewsType.ASSIGNED, name, Professions.key(villager), pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
		level.playSound(null, villager.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 1f, 1f);
		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 12, 0.4, 0.3, 0.4, 0);
		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, villager.getX(), villager.getY() + 1.8, villager.getZ(), 8, 0.3, 0.3, 0.3, 0);
		player.sendSystemMessage(Component.translatable("villageboard.contract.signed",
				VillageManager.whoCap(name), profession.get().value().name(), pos.getX(), pos.getY(), pos.getZ()));
		return InteractionResult.SUCCESS;
	}

	// ------------------------------------------------------------------ liaison

	/**
	 * Affecte le villageois au poste : l'occupant éventuel est délogé, l'ancien poste libéré,
	 * le métier ajusté si besoin.
	 *
	 * @return le villageois délogé, ou null
	 */
	private Villager bind(Villager villager, ServerLevel level, BlockPos pos, Holder<VillagerProfession> profession) {
		Villager evicted = null;
		manager.bypassFreeze = true;
		try {
			PoiManager poi = level.getPoiManager();
			GlobalPos site = GlobalPos.of(level.dimension(), pos);
			boolean alreadyThere = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).map(site::equals).orElse(false);
			if (!alreadyThere) {
				Villager other = holderOf(level, pos);
				if (other != null && other != villager) {
					other.releasePoi(MemoryModuleType.JOB_SITE);
					other.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
					if (isBound(other)) {
						unbind(other, false);
					}
					evicted = other;
				} else if (poi.getInRange(t -> true, pos, 0, PoiManager.Occupancy.IS_OCCUPIED).anyMatch(r -> r.getPos().equals(pos))) {
					poi.release(pos); // ticket orphelin : personne ne déclare ce poste
				}
				villager.releasePoi(MemoryModuleType.JOB_SITE);
				villager.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
				villager.getBrain().eraseMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
				poi.take(t -> true, (t, p) -> p.equals(pos), pos, 1);
				villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, site);
			}
			if (!villager.getVillagerData().profession().is(profession)) {
				villager.setVillagerData(villager.getVillagerData().withProfession(profession));
				villager.refreshBrain(level);
			}
			villager.setAttached(Attachments.BOUND_SITE, site);
		} finally {
			manager.bypassFreeze = false;
		}
		VillagerRecord r = manager.record(villager.getUUID());
		if (r != null) {
			manager.refresh(r, villager);
		}
		return evicted;
	}

	void unbind(Villager villager, boolean announce) {
		villager.removeAttached(Attachments.BOUND_SITE);
		VillagerRecord r = manager.record(villager.getUUID());
		if (r != null) {
			manager.refresh(r, villager);
			if (announce) {
				manager.news(manager.villageOf(villager.getUUID()), NewsType.UNBOUND, r.displayName(), r.profession);
			}
		}
	}

	/** Réaffecte les villageois liés qui ont perdu leur poste de vue ; rompt le lien si le poste a disparu. */
	void maintain() {
		List<VillagerRecord> bound = new ArrayList<>();
		for (Village v : manager.villages()) {
			for (VillagerRecord r : v.villagers.values()) {
				if (r.boundSite != null) {
					bound.add(r);
				}
			}
		}
		for (VillagerRecord r : bound) {
			Villager villager = manager.live(r);
			if (villager == null) {
				continue;
			}
			GlobalPos site = villager.getAttached(Attachments.BOUND_SITE);
			ServerLevel level = site == null ? null : server.getLevel(site.dimension());
			if (level == null) {
				manager.refresh(r, villager);
				continue;
			}
			if (!level.isLoaded(site.pos())) {
				continue;
			}
			Optional<Holder<VillagerProfession>> profession = level.getPoiManager().getType(site.pos())
					.flatMap(p -> professionFor(level, p));
			if (profession.isEmpty()) {
				unbind(villager, true);
				continue;
			}
			boolean atSite = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).map(site::equals).orElse(false);
			if (!atSite || !villager.getVillagerData().profession().is(profession.get())) {
				bind(villager, level, site.pos(), profession.get());
			}
		}
	}

	/** Le villageois qui occupe ce poste (d'après sa mémoire), s'il y en a un à proximité. */
	private Villager holderOf(ServerLevel level, BlockPos pos) {
		GlobalPos site = GlobalPos.of(level.dimension(), pos);
		List<Villager> found = level.getEntities(EntityTypes.VILLAGER, new AABB(pos).inflate(HOLDER_SEARCH_RADIUS),
				v -> v.isAlive() && v.getBrain().getMemory(MemoryModuleType.JOB_SITE).map(site::equals).orElse(false));
		return found.isEmpty() ? null : found.getFirst();
	}

	/** Le métier qui correspond à ce type de poste (pupitre → bibliothécaire…). */
	private static Optional<Holder<VillagerProfession>> professionFor(ServerLevel level, Holder<PoiType> poi) {
		if (!VillagerProfession.ALL_ACQUIRABLE_JOBS.test(poi)) {
			return Optional.empty();
		}
		return level.registryAccess().lookupOrThrow(Registries.VILLAGER_PROFESSION).listElements()
				.filter(p -> !p.is(VillagerProfession.NONE) && p.value().heldJobSite().test(poi))
				.<Holder<VillagerProfession>>map(p -> p)
				.findFirst();
	}
}
