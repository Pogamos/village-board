package fr.villageboard.village;

import fr.villageboard.item.ContractKind;
import fr.villageboard.item.ContractTarget;
import fr.villageboard.item.ModItems;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Liaisons permanentes d'un villageois à un poste de travail (contrat de travail) ou à un lit (bail de logement).
 * Un villageois lié garde ce poste ou ce lit : s'il le perd de vue (lit occupé par un joueur, chemin bloqué…),
 * il y est réinstallé ; si le bloc est détruit, le lien est rompu et la gazette l'annonce.
 */
final class Assignments {

	/** Mémoire du villageois et donnée attachée qui correspondent à chaque type de liaison. */
	enum Kind {
		WORK(MemoryModuleType.JOB_SITE, Attachments.BOUND_SITE),
		HOME(MemoryModuleType.HOME, Attachments.BOUND_HOME);

		final MemoryModuleType<GlobalPos> memory;
		final AttachmentType<GlobalPos> attachment;

		Kind(MemoryModuleType<GlobalPos> memory, AttachmentType<GlobalPos> attachment) {
			this.memory = memory;
			this.attachment = attachment;
		}

		static Kind of(ContractKind kind) {
			return kind == ContractKind.HOME ? HOME : WORK;
		}
	}

	private static final double MAX_ASSIGN_DISTANCE = 48;
	private static final double HOLDER_SEARCH_RADIUS = 64;

	private final VillageManager manager;
	private final MinecraftServer server;

	Assignments(VillageManager manager, MinecraftServer server) {
		this.manager = manager;
		this.server = server;
	}

	static boolean isBound(Villager villager, Kind kind) {
		return villager.hasAttached(kind.attachment);
	}

	// ------------------------------------------------------------------ contrat / bail

	/** Clic droit sur un villageois : il est inscrit sur le contrat ou le bail. */
	void selectVillager(ServerPlayer player, ItemStack stack, Villager villager, ContractKind kind) {
		String profession = Professions.key(villager);
		if (kind == ContractKind.WORK && villager.isBaby()) {
			player.sendOverlayMessage(Component.translatable("villageboard.contract.child"));
			return;
		}
		if (kind == ContractKind.WORK && profession.equals(Professions.NITWIT)) {
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
		player.sendOverlayMessage(Component.translatable(kind.keyPrefix + "selected", VillageManager.whoCap(name)));
	}

	/**
	 * Clic droit sur un bloc. Si c'est le bon type de bloc (poste de travail pour un contrat, lit pour un bail) :
	 * y lie le villageois inscrit, ou indique qui l'occupe si rien n'est inscrit. Sinon PASS (le bloc réagit normalement).
	 */
	InteractionResult useOnBlock(ServerPlayer player, ItemStack stack, BlockPos clicked, ContractKind contract) {
		ServerLevel level = player.level();
		Kind kind = Kind.of(contract);
		BlockPos pos;
		Optional<Holder<VillagerProfession>> profession = Optional.empty();
		if (kind == Kind.WORK) {
			pos = clicked;
			profession = level.getPoiManager().getType(pos).flatMap(p -> Professions.forPoi(level, p));
			if (profession.isEmpty()) {
				return InteractionResult.PASS;
			}
		} else {
			pos = bedHead(level, clicked);
			if (pos == null) {
				return InteractionResult.PASS;
			}
		}

		ContractTarget target = stack.get(ModItems.CONTRACT_TARGET);
		if (target == null) {
			Villager holder = holderOf(level, pos, kind);
			Component free = kind == Kind.WORK
					? Component.translatable("villageboard.contract.site_free", profession.get().value().name())
					: Component.translatable("villageboard.lease.bed_free");
			player.sendOverlayMessage(holder == null ? free
					: Component.translatable(contract.keyPrefix + "site_taken", VillageManager.whoCap(manager.displayName(holder))));
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
			player.sendOverlayMessage(Component.translatable(contract.keyPrefix + "too_far", (int) MAX_ASSIGN_DISTANCE));
			return InteractionResult.SUCCESS;
		}
		if (kind == Kind.WORK) {
			boolean changesJob = !villager.getVillagerData().profession().is(profession.get());
			if (changesJob && !Professions.key(villager).equals(Professions.NONE)
					&& (villager.getVillagerXp() > 0 || villager.getVillagerData().level() > 1)) {
				player.sendOverlayMessage(Component.translatable("villageboard.contract.experienced",
						VillageManager.whoCap(manager.displayName(villager)), villager.getVillagerData().profession().value().name()));
				return InteractionResult.SUCCESS;
			}
		}

		Villager evicted = bind(villager, level, pos, kind, profession.orElse(null));
		if (evicted != null) {
			player.sendSystemMessage(Component.translatable(contract.keyPrefix + "evicted", VillageManager.whoCap(manager.displayName(evicted))));
		}
		stack.remove(ModItems.CONTRACT_TARGET);
		String name = manager.displayName(villager);
		String coords = pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
		if (kind == Kind.WORK) {
			manager.news(village, NewsType.ASSIGNED, name, Professions.key(villager), coords);
			player.sendSystemMessage(Component.translatable("villageboard.contract.signed",
					VillageManager.whoCap(name), profession.get().value().name(), pos.getX(), pos.getY(), pos.getZ()));
		} else {
			manager.news(village, NewsType.HOME_ASSIGNED, name, coords);
			player.sendSystemMessage(Component.translatable("villageboard.lease.signed",
					VillageManager.whoCap(name), pos.getX(), pos.getY(), pos.getZ()));
		}
		level.playSound(null, villager.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 1f, 1f);
		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, 12, 0.4, 0.3, 0.4, 0);
		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, villager.getX(), villager.getY() + 1.8, villager.getZ(), 8, 0.3, 0.3, 0.3, 0);
		return InteractionResult.SUCCESS;
	}

	/** La tête du lit cliqué (c'est elle qui porte le point d'intérêt « maison »), ou null si ce n'est pas un lit. */
	private static BlockPos bedHead(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof BedBlock)) {
			return null;
		}
		BlockPos head = state.getValue(BedBlock.PART) == BedPart.HEAD ? pos : pos.relative(BedBlock.getConnectedDirection(state));
		return level.getPoiManager().getType(head).filter(t -> t.is(PoiTypes.HOME)).isPresent() ? head : null;
	}

	// ------------------------------------------------------------------ liaison

	/**
	 * Lie le villageois au bloc : l'occupant éventuel est délogé, l'ancien poste ou lit libéré,
	 * le métier ajusté si besoin (poste de travail seulement).
	 *
	 * @return le villageois délogé, ou null
	 */
	private Villager bind(Villager villager, ServerLevel level, BlockPos pos, Kind kind, Holder<VillagerProfession> profession) {
		Villager evicted = null;
		manager.bypassFreeze = true;
		try {
			PoiManager poi = level.getPoiManager();
			GlobalPos site = GlobalPos.of(level.dimension(), pos);
			boolean alreadyThere = villager.getBrain().getMemory(kind.memory).map(site::equals).orElse(false);
			if (!alreadyThere) {
				Villager other = holderOf(level, pos, kind);
				if (other != null && other != villager) {
					other.releasePoi(kind.memory);
					other.getBrain().eraseMemory(kind.memory);
					if (isBound(other, kind)) {
						unbind(other, kind, false);
					}
					evicted = other;
				} else if (poi.getInRange(t -> true, pos, 0, PoiManager.Occupancy.IS_OCCUPIED).anyMatch(r -> r.getPos().equals(pos))) {
					poi.release(pos); // ticket orphelin : personne ne déclare ce poste ou ce lit
				}
				villager.releasePoi(kind.memory);
				villager.getBrain().eraseMemory(kind.memory);
				if (kind == Kind.WORK) {
					villager.getBrain().eraseMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
				}
				poi.take(t -> true, (t, p) -> p.equals(pos), pos, 1);
				villager.getBrain().setMemory(kind.memory, site);
			}
			if (profession != null && !villager.getVillagerData().profession().is(profession)) {
				villager.setVillagerData(villager.getVillagerData().withProfession(profession));
				villager.refreshBrain(level);
			}
			villager.setAttached(kind.attachment, site);
		} finally {
			manager.bypassFreeze = false;
		}
		VillagerRecord r = manager.record(villager.getUUID());
		if (r != null) {
			manager.refresh(r, villager);
		}
		return evicted;
	}

	void unbind(Villager villager, Kind kind, boolean announce) {
		villager.removeAttached(kind.attachment);
		VillagerRecord r = manager.record(villager.getUUID());
		if (r == null) {
			return;
		}
		manager.refresh(r, villager);
		if (announce) {
			Village village = manager.villageOf(villager.getUUID());
			if (kind == Kind.WORK) {
				manager.news(village, NewsType.UNBOUND, r.displayName(), r.profession);
			} else {
				manager.news(village, NewsType.HOME_UNBOUND, r.displayName());
			}
		}
	}

	/** Réinstalle les villageois liés qui ont perdu leur poste ou leur lit de vue ; rompt le lien si le bloc a disparu. */
	void maintain() {
		List<VillagerRecord> bound = new ArrayList<>();
		for (Village v : manager.villages()) {
			for (VillagerRecord r : v.villagers.values()) {
				if (r.boundSite != null || r.boundHome != null) {
					bound.add(r);
				}
			}
		}
		for (VillagerRecord r : bound) {
			Villager villager = manager.live(r);
			if (villager == null) {
				continue;
			}
			for (Kind kind : Kind.values()) {
				if (isBound(villager, kind)) {
					maintain(villager, kind);
				}
			}
			manager.refresh(r, villager);
		}
	}

	private void maintain(Villager villager, Kind kind) {
		GlobalPos site = villager.getAttached(kind.attachment);
		ServerLevel level = server.getLevel(site.dimension());
		if (level == null || !level.isLoaded(site.pos())) {
			return;
		}
		Optional<Holder<VillagerProfession>> profession = Optional.empty();
		boolean exists;
		if (kind == Kind.WORK) {
			profession = level.getPoiManager().getType(site.pos()).flatMap(p -> Professions.forPoi(level, p));
			exists = profession.isPresent();
		} else {
			exists = level.getPoiManager().getType(site.pos()).filter(t -> t.is(PoiTypes.HOME)).isPresent();
		}
		if (!exists) {
			unbind(villager, kind, true);
			return;
		}
		boolean atSite = villager.getBrain().getMemory(kind.memory).map(site::equals).orElse(false);
		boolean rightJob = profession.isEmpty() || villager.getVillagerData().profession().is(profession.get());
		if (!atSite || !rightJob) {
			bind(villager, level, site.pos(), kind, profession.orElse(null));
		}
	}

	/** Le villageois qui occupe ce poste ou ce lit (d'après sa mémoire), s'il y en a un à proximité. */
	private Villager holderOf(ServerLevel level, BlockPos pos, Kind kind) {
		GlobalPos site = GlobalPos.of(level.dimension(), pos);
		List<Villager> found = level.getEntities(EntityTypes.VILLAGER, new AABB(pos).inflate(HOLDER_SEARCH_RADIUS),
				v -> v.isAlive() && v.getBrain().getMemory(kind.memory).map(site::equals).orElse(false));
		return found.isEmpty() ? null : found.getFirst();
	}
}
