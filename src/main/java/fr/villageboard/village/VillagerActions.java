package fr.villageboard.village;

import fr.villageboard.Config;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.TeamColor;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Actions du tableau sur un villageois : renommer, localiser, verrouiller, réinitialiser. */
final class VillagerActions {

	private static final String GLOW_TEAM = "villageboard_glow";
	private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

	/**
	 * Boussole d'un joueur : vers un villageois (sa position est suivie), ou vers un bloc (poste, lit) désigné par
	 * {@code label} et signalé par une colonne de particules.
	 */
	private record Tracker(VillagerRecord target, String dimension, BlockPos block, Component label, int endTick) {
	}

	private final VillageManager manager;
	private final MinecraftServer server;
	private final Map<UUID, Tracker> trackers = new HashMap<>();
	/** Entrée d'équipe (UUID du villageois) → tick de fin du contour. */
	private final Map<String, Integer> glowing = new HashMap<>();
	private int now;

	VillagerActions(VillageManager manager, MinecraftServer server) {
		this.manager = manager;
		this.server = server;
	}

	// ------------------------------------------------------------------ renommer

	/** Un nom vide retire le nom personnalisé : le villageois redevient « sans nom ». */
	void rename(Villager villager, String input) {
		String before = manager.displayName(villager);
		String name = VillageManager.clean(input);
		villager.setCustomName(name.isEmpty() ? null : Component.literal(name));
		VillagerRecord r = manager.observe(villager, false);
		String after = manager.displayName(villager);
		Village village = manager.villageOf(villager.getUUID());
		if (r != null && village != null && !before.equals(after)) {
			manager.news(village, NewsType.RENAMED, before, after);
		}
	}

	// ------------------------------------------------------------------ localiser

	void locate(ServerPlayer player, VillagerRecord r, Villager villager) {
		int seconds = Config.get().glowSeconds;
		if (villager != null) {
			manager.refresh(r, villager);
			glow(villager, seconds * 20);
		}
		trackers.put(player.getUUID(), new Tracker(r, null, null, null, now + seconds * 20));
		player.sendSystemMessage(villager != null
				? Component.translatable("villageboard.msg.locate", VillageManager.whoCap(r.displayName()), (int) r.x, (int) r.y, (int) r.z, seconds)
				: Component.translatable("villageboard.msg.locate_unloaded", VillageManager.whoCap(r.displayName()), (int) r.x, (int) r.y, (int) r.z));
	}

	/** Localise le poste de travail ou le lit d'un villageois : boussole, et colonne de particules au-dessus du bloc. */
	void locatePlace(ServerPlayer player, VillagerRecord r, BlockPos pos, boolean bed) {
		int seconds = Config.get().glowSeconds;
		String key = bed ? "villageboard.place.bed" : "villageboard.place.site";
		Component label = r.displayName().isEmpty()
				? Component.translatable(key + ".unnamed")
				: Component.translatable(key, r.displayName());
		trackers.put(player.getUUID(), new Tracker(r, r.dimension, pos, label, now + seconds * 20));
		player.sendSystemMessage(Component.translatable("villageboard.msg.locate_place", label, pos.getX(), pos.getY(), pos.getZ(), seconds));
	}

	private void glow(Villager villager, int ticks) {
		villager.addEffect(new MobEffectInstance(MobEffects.GLOWING, ticks, 0, false, false));
		// Une équipe dédiée colore le contour en doré, sans toucher aux villageois déjà dans une équipe.
		Scoreboard scoreboard = server.getScoreboard();
		PlayerTeam team = scoreboard.getPlayerTeam(GLOW_TEAM);
		if (team == null) {
			team = scoreboard.addPlayerTeam(GLOW_TEAM);
			team.setColor(Optional.of(TeamColor.GOLD));
		}
		String entry = villager.getScoreboardName();
		if (scoreboard.getPlayersTeam(entry) == null || glowing.containsKey(entry)) {
			scoreboard.addPlayerToTeam(entry, team);
			glowing.put(entry, now + ticks);
		}
	}

	void tick(int tick) {
		now = tick;
		if (tick % 5 != 0) {
			return;
		}
		Iterator<Map.Entry<UUID, Tracker>> it = trackers.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Tracker> e = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
			if (player == null || tick > e.getValue().endTick()) {
				it.remove();
				continue;
			}
			Tracker tracker = e.getValue();
			player.sendOverlayMessage(compass(player, tracker));
			if (tracker.block() != null && tracker.dimension().equals(VillageManager.dim(player.level()))) {
				BlockPos b = tracker.block();
				player.level().sendParticles(player, ParticleTypes.END_ROD, true, true,
						b.getX() + 0.5, b.getY() + 2.5, b.getZ() + 0.5, 4, 0.1, 1.5, 0.1, 0.01);
			}
		}
		glowing.entrySet().removeIf(e -> {
			if (tick <= e.getValue()) {
				return false;
			}
			unglow(e.getKey());
			return true;
		});
	}

	/** « Héloïse  ↗  42 blocs » ou « Lit d'Héloïse  ↗  42 blocs », flèche relative au regard du joueur. */
	private Component compass(ServerPlayer player, Tracker tracker) {
		Component who;
		String dimension;
		Vec3 target;
		if (tracker.block() != null) {
			who = tracker.label();
			dimension = tracker.dimension();
			target = Vec3.atCenterOf(tracker.block());
		} else {
			VillagerRecord r = tracker.target();
			Villager villager = manager.live(r);
			if (villager != null) {
				manager.refresh(r, villager);
			}
			who = VillageManager.whoCap(r.displayName());
			dimension = r.dimension;
			target = new Vec3(r.x, r.y, r.z);
		}
		if (dimension == null || !dimension.equals(VillageManager.dim(player.level()))) {
			return Component.translatable("villageboard.compass.other_dimension", who).withStyle(ChatFormatting.GOLD);
		}
		double distance = player.position().distanceTo(target);
		if (distance < 3) {
			return Component.translatable(tracker.block() != null ? "villageboard.compass.here_place" : "villageboard.compass.here", who)
					.withStyle(ChatFormatting.GREEN);
		}
		double dx = target.x - player.getX();
		double dz = target.z - player.getZ();
		double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
		double relative = ((targetYaw - player.getYRot()) % 360 + 360) % 360;
		String arrow = ARROWS[(int) Math.round(relative / 45) % 8];
		return Component.translatable("villageboard.compass", who, arrow, (int) distance).withStyle(ChatFormatting.GOLD);
	}

	private void unglow(String entry) {
		Scoreboard scoreboard = server.getScoreboard();
		PlayerTeam team = scoreboard.getPlayerTeam(GLOW_TEAM);
		if (team != null && scoreboard.getPlayersTeam(entry) == team) {
			scoreboard.removePlayerFromTeam(entry, team);
		}
	}

	void cleanup() {
		glowing.keySet().forEach(this::unglow);
		glowing.clear();
		trackers.clear();
	}

	// ------------------------------------------------------------------ verrou & réinitialisation

	void toggleLock(ServerPlayer player, Villager villager) {
		boolean locked = !VillageManager.isLocked(villager);
		manager.setLocked(villager, locked);
		player.sendOverlayMessage(Component.translatable(
				locked ? "villageboard.msg.locked" : "villageboard.msg.unlocked", VillageManager.who(manager.displayName(villager))));
	}

	/**
	 * Le villageois quitte son poste : le poste est libéré proprement (il redevient disponible),
	 * expérience, niveau et échanges sont perdus, puis il cherche un nouveau travail. Un villageois verrouillé le reste
	 * (son lit aussi) : le prochain poste qu'il trouvera lui sera attitré.
	 */
	void resetJob(ServerPlayer player, Villager villager) {
		String profession = Professions.key(villager);
		if (profession.equals(Professions.NONE) || profession.equals(Professions.NITWIT)) {
			return;
		}
		ServerLevel level = (ServerLevel) villager.level();
		boolean locked = VillageManager.isLocked(villager);
		villager.removeAttached(Attachments.LOCKED); // le temps de quitter le métier
		manager.unbindWork(villager);
		villager.releasePoi(MemoryModuleType.JOB_SITE);
		villager.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
		villager.getBrain().eraseMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
		villager.setVillagerXp(0);
		VillagerData data = villager.getVillagerData();
		villager.setVillagerData(data.withProfession(level.registryAccess(), VillagerProfession.NONE).withLevel(1));
		villager.refreshBrain(level);
		if (locked) {
			villager.setAttached(Attachments.LOCKED, true);
		}
		VillagerRecord r = manager.record(villager.getUUID());
		if (r != null) {
			manager.refresh(r, villager);
		}
		player.sendOverlayMessage(Component.translatable("villageboard.msg.reset", VillageManager.whoCap(manager.displayName(villager))));
	}
}
