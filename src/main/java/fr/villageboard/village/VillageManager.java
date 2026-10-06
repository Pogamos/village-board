package fr.villageboard.village;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import fr.villageboard.Config;
import fr.villageboard.VillageBoard;
import fr.villageboard.block.ModBlocks;
import fr.villageboard.item.ContractKind;
import fr.villageboard.net.BoardView;
import fr.villageboard.net.BorderView;
import fr.villageboard.net.Payloads;
import fr.villageboard.net.VillagerCard;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Registre des villages, côté serveur. Une instance par serveur en cours d'exécution ; toutes les méthodes
 * s'exécutent sur le thread du serveur. Sauvegardé dans &lt;monde&gt;/villageboard/villages.json.
 */
public final class VillageManager {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Village>>() {
	}.getType();
	private static final double BOARD_REACH_SQ = 8 * 8;
	private static final int MAX_BORNES = 64;
	private static final int AUTOSAVE_TICKS = 60 * 20;
	private static final int BOUND_CHECK_TICKS = 40;

	private static VillageManager instance;

	private final MinecraftServer server;
	private final Path file;
	private final Config config = Config.get();
	private final Map<String, Village> villages = new LinkedHashMap<>();
	/** Village auquel appartient chaque villageois recensé. */
	private final Map<UUID, Village> index = new HashMap<>();
	/** Parents d'un bébé, entre sa création et son apparition dans le monde. */
	private final Map<UUID, Pending> pendingParents = new HashMap<>();
	private final Genealogy genealogy;
	private final Marriages marriages;
	private final VillagerActions actions;
	private final Assignments assignments;
	/** Vrai pendant que le mod change lui-même le métier d'un villageois verrouillé ou lié. */
	boolean bypassFreeze;
	private int ticks;

	private VillageManager(MinecraftServer server) {
		this.server = server;
		this.file = server.getWorldPath(LevelResource.ROOT).resolve("villageboard").resolve("villages.json");
		this.genealogy = new Genealogy(file.resolveSibling("family.json"));
		this.marriages = new Marriages(this);
		this.actions = new VillagerActions(this, server);
		this.assignments = new Assignments(this, server);
	}

	public static VillageManager get() {
		return instance;
	}

	static void start(MinecraftServer server) {
		instance = new VillageManager(server);
		instance.load();
	}

	static void stop() {
		if (instance != null) {
			instance.actions.cleanup();
			instance.save(true);
			instance = null;
		}
	}

	// ------------------------------------------------------------------ persistance

	private void load() {
		genealogy.load();
		if (!Files.exists(file)) {
			return;
		}
		try {
			Map<String, Village> data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), DATA_TYPE);
			if (data != null) {
				villages.putAll(data);
				for (Village v : villages.values()) {
					v.villagers.keySet().forEach(uuid -> index.put(UUID.fromString(uuid), v));
					// Type d'actualité inconnu de cette version (écrit par une autre version du mod) : ignoré.
					v.news.removeIf(n -> n == null || n.type() == null);
				}
			}
			genealogy.migrate(villages.values());
			VillageBoard.LOGGER.info("{} village(s) chargé(s)", villages.size());
		} catch (IOException | RuntimeException e) {
			VillageBoard.LOGGER.error("Impossible de lire {}", file, e);
		}
	}

	void save(boolean force) {
		genealogy.save(force);
		if (!force && villages.values().stream().noneMatch(v -> v.dirty)) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling("villages.json.tmp");
			Files.writeString(tmp, GSON.toJson(villages, DATA_TYPE), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			villages.values().forEach(v -> v.dirty = false);
		} catch (IOException e) {
			VillageBoard.LOGGER.error("Impossible d'enregistrer {}", file, e);
		}
	}

	void tick() {
		ticks++;
		if (ticks % (config.scanIntervalSeconds * 20) == 0) {
			scan();
		}
		if (ticks % BOUND_CHECK_TICKS == 0) {
			assignments.maintain();
		}
		if (ticks % AUTOSAVE_TICKS == 0) {
			save(false);
		}
		actions.tick(ticks);
	}

	// ------------------------------------------------------------------ recherche

	public Collection<Village> villages() {
		return villages.values();
	}

	Genealogy genealogy() {
		return genealogy;
	}

	/** Appelé par le mixin : ces deux villageois peuvent-ils avoir un enfant ensemble (couple, proches parents) ? */
	public boolean mayBreed(Villager a, Villager b) {
		return genealogy.allowed(a.getUUID(), b.getUUID());
	}

	public Village village(String id) {
		return villages.get(id);
	}

	static String dim(Level level) {
		return level.dimension().identifier().toString();
	}

	ServerLevel level(String dimension) {
		return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension)));
	}

	/** Le village dont le territoire contient ce point (le plus proche de son tableau en cas de chevauchement). */
	public Village villageAt(String dimension, double x, double z) {
		Village best = null;
		double bestDist = Double.POSITIVE_INFINITY;
		for (Village v : villages.values()) {
			if (v.contains(dimension, x, z, config.defaultRadius)) {
				double d = v.boardPos().distToCenterSqr(x, v.boardPos().getY(), z);
				if (d < bestDist) {
					best = v;
					bestDist = d;
				}
			}
		}
		return best;
	}

	public Village boardAt(Level level, BlockPos pos) {
		String dimension = dim(level);
		long key = pos.asLong();
		for (Village v : villages.values()) {
			if (v.board == key && !v.boardMissing && v.dimension.equals(dimension)) {
				return v;
			}
		}
		return null;
	}

	private Village villageOfBorne(Level level, BlockPos pos) {
		String dimension = dim(level);
		for (Village v : villages.values()) {
			if (v.dimension.equals(dimension) && v.bornes.contains(pos.asLong())) {
				return v;
			}
		}
		return null;
	}

	public Village villageOf(UUID villager) {
		return index.get(villager);
	}

	public VillagerRecord record(UUID villager) {
		Village v = index.get(villager);
		return v == null ? null : v.villagers.get(villager.toString());
	}

	/** Le villageois s'il est actuellement chargé. */
	Villager live(VillagerRecord r) {
		ServerLevel level = r.dimension == null ? null : level(r.dimension);
		if (level == null) {
			return null;
		}
		return level.getEntity(UUID.fromString(r.uuid)) instanceof Villager v && v.isAlive() ? v : null;
	}

	// ------------------------------------------------------------------ droits

	boolean isFounderOrOp(ServerPlayer player, Village v) {
		return player.getUUID().toString().equals(v.founder)
				|| player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	boolean canManage(ServerPlayer player, Village v) {
		return "everyone".equalsIgnoreCase(config.managers) || isFounderOrOp(player, v);
	}

	// ------------------------------------------------------------------ tableau & bornes

	/**
	 * Peut-on poser un tableau ici ? Non sur le territoire d'un village qui a déjà son tableau. Sur le territoire d'un
	 * village sans tableau (ou près de ses bornes), le tableau s'y rattache : il faut alors avoir le droit de le gérer.
	 */
	public boolean canFound(ServerLevel level, BlockPos pos, Player player) {
		String dimension = dim(level);
		ServerPlayer serverPlayer = player instanceof ServerPlayer p ? p : null;
		for (Village other : villages.values()) {
			if (!other.boardMissing && other.contains(dimension, pos.getX() + 0.5, pos.getZ() + 0.5, config.defaultRadius)) {
				if (serverPlayer != null) {
					serverPlayer.sendOverlayMessage(Component.translatable("villageboard.msg.inside_territory", other.name));
				}
				return false;
			}
		}
		Village orphan = orphanFor(level, pos);
		if (orphan != null && serverPlayer != null && !canManage(serverPlayer, orphan)) {
			serverPlayer.sendOverlayMessage(Component.translatable("villageboard.msg.no_permission"));
			return false;
		}
		return true;
	}

	/**
	 * Village sans tableau auquel un tableau posé ici se rattache : celui dont le territoire contient ce point, ou dont
	 * toutes les bornes sont à moins de {@code maxBorneDistance} blocs ; le plus proche de son ancien tableau.
	 */
	private Village orphanFor(Level level, BlockPos pos) {
		String dimension = dim(level);
		double x = pos.getX() + 0.5;
		double z = pos.getZ() + 0.5;
		double maxSq = (double) config.maxBorneDistance * config.maxBorneDistance;
		Village best = null;
		double bestDist = Double.POSITIVE_INFINITY;
		for (Village v : villages.values()) {
			if (!v.boardMissing || !v.dimension.equals(dimension)) {
				continue;
			}
			boolean near = v.contains(dimension, x, z, config.defaultRadius)
					|| !v.bornes.isEmpty() && v.bornes.stream().map(BlockPos::of).allMatch(b -> {
						double dx = b.getX() + 0.5 - x;
						double dz = b.getZ() + 0.5 - z;
						return dx * dx + dz * dz <= maxSq;
					});
			double d = v.boardPos().distToCenterSqr(x, v.boardPos().getY(), z);
			if (near && d < bestDist) {
				best = v;
				bestDist = d;
			}
		}
		return best;
	}

	public void found(ServerLevel level, BlockPos pos, LivingEntity placer, ItemStack stack) {
		if (boardAt(level, pos) != null) {
			return;
		}
		Village orphan = orphanFor(level, pos);
		if (orphan != null) {
			rebind(orphan, pos, placer);
			return;
		}
		Village v = new Village();
		v.id = UUID.randomUUID().toString().substring(0, 8);
		String founderName = placer instanceof Player p ? p.getPlainTextName() : "?";
		Component custom = stack.getCustomName();
		v.name = custom != null ? clean(custom.getString()) : "Village de " + founderName;
		v.dimension = dim(level);
		v.board = pos.asLong();
		v.founder = placer == null ? "" : placer.getUUID().toString();
		v.founderName = founderName;
		v.foundedAt = System.currentTimeMillis();
		villages.put(v.id, v);
		for (Villager villager : level.getEntities(EntityTypes.VILLAGER, Entity::isAlive)) {
			if (!index.containsKey(villager.getUUID()) && v.contains(v.dimension, villager.getX(), villager.getZ(), config.defaultRadius)) {
				register(v, villager);
			}
		}
		news(v, NewsType.FOUNDED, v.name, String.valueOf(v.villagers.size()));
		updateFacilities(level, v);
		save(true);
		broadcastBorders();
		if (placer instanceof ServerPlayer player) {
			player.sendSystemMessage(Component.translatable("villageboard.msg.founded",
					v.name, v.villagers.size(), config.defaultRadius));
		}
	}

	/** Un tableau reposé rattache le village sans tableau, avec ses bornes, ses habitants et sa gazette. */
	private void rebind(Village v, BlockPos pos, LivingEntity placer) {
		v.board = pos.asLong();
		v.boardMissing = false;
		v.bornesChanged();
		news(v, NewsType.BOARD_MOVED, pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
		save(true);
		broadcastBorders();
		if (placer instanceof ServerPlayer player) {
			player.sendSystemMessage(Component.translatable("villageboard.msg.board_rebound",
					v.name, v.bornes.size(), v.villagers.size()));
			if (v.bornes.size() >= 3 && !v.contains(v.dimension, pos.getX() + 0.5, pos.getZ() + 0.5, config.defaultRadius)) {
				message(player, Component.translatable("villageboard.msg.board_outside"));
			}
		}
	}

	/** Le tableau a disparu (cassé par un joueur, /setblock…) : le village est conservé, en attente d'un nouveau tableau. */
	void boardRemoved(Village v, ServerPlayer by) {
		if (v.boardMissing) {
			return;
		}
		v.boardMissing = true;
		v.dirty = true;
		news(v, NewsType.BOARD_REMOVED, by == null ? "" : by.getPlainTextName());
		save(true);
		broadcastBorders();
		if (by != null) {
			by.sendSystemMessage(Component.translatable("villageboard.msg.board_removed", v.name, v.id));
		}
	}

	public void addBorne(ServerLevel level, BlockPos pos, LivingEntity placer) {
		ServerPlayer player = placer instanceof ServerPlayer p ? p : null;
		String dimension = dim(level);
		Village best = null;
		double bestDist = (double) config.maxBorneDistance * config.maxBorneDistance;
		for (Village v : villages.values()) {
			if (v.dimension.equals(dimension)) {
				double d = v.boardPos().distToCenterSqr(pos.getX() + 0.5, v.boardPos().getY(), pos.getZ() + 0.5);
				if (d <= bestDist) {
					best = v;
					bestDist = d;
				}
			}
		}
		if (best == null) {
			message(player, Component.translatable("villageboard.msg.borne_orphan", config.maxBorneDistance));
			return;
		}
		if (player != null && !canManage(player, best)) {
			message(player, Component.translatable("villageboard.msg.no_permission"));
			return;
		}
		if (best.bornes.size() >= MAX_BORNES) {
			message(player, Component.translatable("villageboard.msg.borne_max", MAX_BORNES));
			return;
		}
		best.bornes.add(pos.asLong());
		best.bornesChanged();
		int count = best.bornes.size();
		news(best, NewsType.TERRITORY, String.valueOf(count));
		broadcastBorders();
		message(player, Component.translatable("villageboard.msg.borne_added", count, best.name));
		if (count < 3) {
			message(player, Component.translatable("villageboard.msg.borne_more", 3 - count));
		} else if (!best.contains(dimension, best.boardPos().getX() + 0.5, best.boardPos().getZ() + 0.5, config.defaultRadius)) {
			message(player, Component.translatable("villageboard.msg.board_outside"));
		}
	}

	/** @return false pour empêcher le joueur de casser ce bloc */
	boolean allowBreak(Level level, Player player, BlockPos pos) {
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return true;
		}
		Village board = boardAt(level, pos);
		if (board != null && !isFounderOrOp(serverPlayer, board)) {
			serverPlayer.sendOverlayMessage(Component.translatable("villageboard.msg.board_protected", board.founderName));
			return false;
		}
		Village borne = villageOfBorne(level, pos);
		if (borne != null && !canManage(serverPlayer, borne)) {
			serverPlayer.sendOverlayMessage(Component.translatable("villageboard.msg.no_permission"));
			return false;
		}
		return true;
	}

	void afterBreak(Level level, Player player, BlockPos pos) {
		Village board = boardAt(level, pos);
		if (board != null) {
			boardRemoved(board, player instanceof ServerPlayer p ? p : null);
			return;
		}
		Village borne = villageOfBorne(level, pos);
		if (borne != null) {
			borne.bornes.remove(pos.asLong());
			borne.bornesChanged();
			news(borne, NewsType.TERRITORY, String.valueOf(borne.bornes.size()));
			broadcastBorders();
		}
	}

	void dissolve(Village village) {
		villages.remove(village.id);
		village.villagers.keySet().forEach(uuid -> index.remove(UUID.fromString(uuid)));
		save(true);
		broadcastBorders();
	}

	public void openBoard(ServerPlayer player, BlockPos pos) {
		Village v = boardAt(player.level(), pos);
		if (v == null) {
			// Tableau sans village (registre perdu, ou posé par commande) : on le refonde.
			if (!canFound(player.level(), pos, player)) {
				return;
			}
			found(player.level(), pos, player, ItemStack.EMPTY);
			v = boardAt(player.level(), pos);
		}
		sendView(player, v);
	}

	void sendView(ServerPlayer player, Village v) {
		if (!ServerPlayNetworking.canSend(player, Payloads.OpenBoard.TYPE)) {
			player.sendSystemMessage(Component.literal("Installe le mod Village Board pour consulter le tableau de la mairie."));
			return;
		}
		ServerPlayNetworking.send(player, new Payloads.OpenBoard(view(v, player)));
	}

	/** Fiche résumée envoyée à l'ouverture des échanges avec un villageois (appelé par le mixin). */
	public void sendTradeCard(ServerPlayer player, Villager villager) {
		if (!ServerPlayNetworking.canSend(player, VillagerCard.TYPE)) {
			return;
		}
		VillagerRecord r = observe(villager, true);
		Village village = index.get(villager.getUUID());
		UUID uuid = villager.getUUID();
		BlockPos home = villager.getBrain().getMemory(MemoryModuleType.HOME).filter(this::bedExists).map(GlobalPos::pos).orElse(null);
		int couple = 0;
		String partner = "";
		List<String> parents = List.of();
		Kin k = genealogy.get(uuid);
		if (k != null) {
			if (k.spouse != null) {
				couple = 1;
				Kin s = genealogy.get(UUID.fromString(k.spouse));
				partner = s == null ? "" : s.name;
			} else if (!k.widowed.isEmpty()) {
				couple = 2;
				Kin s = genealogy.get(UUID.fromString(k.widowed.getLast()));
				partner = s == null ? "" : s.name;
			}
			parents = k.parents.stream().map(p -> genealogy.get(UUID.fromString(p))).map(p -> p == null ? "" : p.name).toList();
		}
		ServerPlayNetworking.send(player, new VillagerCard(uuid, displayName(villager), Professions.key(villager),
				Professions.type(villager), villager.getVillagerData().level(), villager.getHealth(), villager.getMaxHealth(),
				village == null ? "" : village.name, home, villager.hasAttached(Attachments.BOUND_HOME),
				memory(villager, MemoryModuleType.JOB_SITE), villager.hasAttached(Attachments.BOUND_SITE), isLocked(villager),
				r == null ? -1 : r.firstSeenDay, r != null && r.born, couple, partner, parents,
				genealogy.childCount(uuid), genealogy.siblingCount(uuid)));
	}

	private BoardView view(Village v, ServerPlayer player) {
		List<BoardView.VillagerView> list = new ArrayList<>();
		for (VillagerRecord r : v.villagers.values()) {
			Villager live = live(r);
			if (live != null) {
				refresh(r, live);
			}
			list.add(new BoardView.VillagerView(
					UUID.fromString(r.uuid), r.displayName(), r.profession, r.type == null ? Professions.DEFAULT_TYPE : r.type,
					r.level, r.baby, r.locked, live != null,
					live != null ? live.getHealth() : 0, live != null ? live.getMaxHealth() : 0,
					live != null ? live.getVillagerXp() : 0,
					live != null && r.employed() ? live.getOffers().size() : 0,
					BlockPos.containing(r.x, r.y, r.z),
					live != null ? memory(live, MemoryModuleType.JOB_SITE) : null,
					r.home == null ? null : BlockPos.of(r.home),
					r.boundSite == null ? null : BlockPos.of(r.boundSite),
					r.boundHome == null ? null : BlockPos.of(r.boundHome),
					r.firstSeenDay, r.born, r.parents == null ? "" : r.parents, r.lastSeen));
		}
		List<BoardView.BedView> beds = Facilities.beds(v).stream()
				.map(b -> new BoardView.BedView(b.pos(), b.occupied()))
				.toList();
		List<BoardView.WorkstationView> workstations = Facilities.workstations(v).stream()
				.map(w -> new BoardView.WorkstationView(w.pos(), w.profession(), w.occupied()))
				.toList();
		List<BoardView.KinView> family = new ArrayList<>();
		genealogy.forVillage(v.id, v.villagers.keySet()).forEach((uuid, k) -> {
			Village elsewhere = k.village == null || k.village.equals(v.id) ? null : villages.get(k.village);
			family.add(new BoardView.KinView(UUID.fromString(uuid), k.name, k.profession, k.type, k.baby,
					k.parents.stream().map(UUID::fromString).toList(), k.born, k.fate, k.fateDay,
					elsewhere == null ? "" : elsewhere.name,
					k.spouse == null ? null : UUID.fromString(k.spouse), k.marriedDay,
					k.divorced.stream().map(UUID::fromString).toList(), k.widowed.stream().map(UUID::fromString).toList()));
		});
		return new BoardView(v.id, v.name, currentDay(), canManage(player, v), v.founderName, v.boardPos(),
				config.defaultRadius, v.polygon(), List.copyOf(v.news), list, beds, workstations,
				Facilities.bells(v), v.golems, family);
	}

	private static BlockPos memory(Villager villager, MemoryModuleType<GlobalPos> type) {
		return villager.getBrain().getMemory(type).map(GlobalPos::pos).orElse(null);
	}

	public void handleAction(ServerPlayer player, Payloads.BoardAction action) {
		Village v = villages.get(action.villageId());
		if (v == null) {
			return;
		}
		if (!dim(player.level()).equals(v.dimension) || player.distanceToSqr(Vec3.atCenterOf(v.boardPos())) > BOARD_REACH_SQ) {
			player.sendOverlayMessage(Component.translatable("villageboard.msg.too_far"));
			return;
		}
		VillagerRecord r = v.villagers.get(action.target().toString());
		Villager live = r == null ? null : live(r);
		switch (action.action()) {
			case REFRESH -> {
			}
			case LOCATE -> {
				if (r != null) {
					actions.locate(player, r, live);
				}
				return;
			}
			default -> {
				if (!canManage(player, v)) {
					player.sendOverlayMessage(Component.translatable("villageboard.msg.no_permission"));
					return;
				}
				switch (action.action()) {
					case RENAME -> {
						if (requireLoaded(player, live)) {
							actions.rename(live, action.arg());
						}
					}
					case LOCK -> {
						if (requireLoaded(player, live)) {
							actions.toggleLock(player, live);
						}
					}
					case RESET -> {
						if (requireLoaded(player, live)) {
							actions.resetJob(player, live);
						}
					}
					case UNBIND -> {
						if (requireLoaded(player, live)) {
							assignments.unbind(live, Assignments.Kind.WORK, true);
						}
					}
					case UNBIND_HOME -> {
						if (requireLoaded(player, live)) {
							assignments.unbind(live, Assignments.Kind.HOME, true);
						}
					}
					case FORGET -> {
						if (r != null && live == null) {
							genealogy.fate(action.target(), Kin.Fate.MISSING, currentDay());
							forget(action.target());
						}
					}
					case RENAME_VILLAGE -> renameVillage(v, action.arg());
					case DIVORCE -> {
						UUID ex = genealogy.divorce(action.target());
						if (ex != null) {
							Kin a = genealogy.get(action.target());
							Kin b = genealogy.get(ex);
							news(v, NewsType.DIVORCED, a.name, b == null ? "" : b.name);
						}
					}
					default -> {
					}
				}
			}
		}
		sendView(player, v);
	}

	private static boolean requireLoaded(ServerPlayer player, Villager villager) {
		if (villager == null) {
			player.sendOverlayMessage(Component.translatable("villageboard.msg.not_loaded"));
			return false;
		}
		return true;
	}

	private void renameVillage(Village v, String input) {
		String name = clean(input);
		if (name.isEmpty() || name.equals(v.name)) {
			return;
		}
		news(v, NewsType.VILLAGE_RENAMED, v.name, name);
		v.name = name;
		v.dirty = true;
		broadcastBorders();
	}

	// ------------------------------------------------------------------ contrat de travail

	public void useContractOnVillager(ServerPlayer player, ItemStack stack, Villager villager, ContractKind kind) {
		if (kind == ContractKind.MARRIAGE) {
			marriages.useOnVillager(player, stack, villager);
		} else {
			assignments.selectVillager(player, stack, villager, kind);
		}
	}

	public InteractionResult useContractOnBlock(ServerPlayer player, ItemStack stack, BlockPos pos, ContractKind kind) {
		return kind == ContractKind.MARRIAGE ? InteractionResult.PASS : assignments.useOnBlock(player, stack, pos, kind);
	}

	/** Rompt le lien avec le poste de travail (réinitialisation du métier) ; le lit attitré est conservé. */
	void unbindWork(Villager villager) {
		assignments.unbind(villager, Assignments.Kind.WORK, false);
	}

	// ------------------------------------------------------------------ frontières

	List<BorderView> borders() {
		return villages.values().stream()
				.map(v -> new BorderView(v.id, v.name, v.dimension, v.boardPos(), config.defaultRadius, v.polygon()))
				.toList();
	}

	void sendBorders(ServerPlayer player) {
		if (ServerPlayNetworking.canSend(player, Payloads.Borders.TYPE)) {
			ServerPlayNetworking.send(player, new Payloads.Borders(borders()));
		}
	}

	void broadcastBorders() {
		server.getPlayerList().getPlayers().forEach(this::sendBorders);
	}

	// ------------------------------------------------------------------ recensement

	private void scan() {
		Set<String> dims = villages.values().stream().map(v -> v.dimension).collect(Collectors.toSet());
		for (String dimension : dims) {
			ServerLevel level = level(dimension);
			if (level == null) {
				continue;
			}
			for (Villager villager : level.getEntities(EntityTypes.VILLAGER, Entity::isAlive)) {
				observe(villager, true);
			}
			validateBornes(level, dimension);
			validateBoards(level, dimension);
			for (Village v : villages.values()) {
				if (v.dimension.equals(dimension)) {
					updateFacilities(level, v);
				}
			}
		}
	}

	/**
	 * Recense lits, postes, cloches et golems ; annonce dans la gazette quand il n'y a plus de lit libre
	 * (naissances bloquées), ou plus de nouveau.
	 */
	private void updateFacilities(ServerLevel level, Village v) {
		Facilities.scan(level, v, config.defaultRadius);
		v.golems = level.getEntities(EntityTypes.IRON_GOLEM,
				g -> g.isAlive() && v.contains(v.dimension, g.getX(), g.getZ(), config.defaultRadius)).size();
		List<Facilities.Bed> beds = Facilities.beds(v);
		int free = (int) beds.stream().filter(b -> !b.occupied()).count();
		Integer changed = Facilities.updateHousingState(v, free);
		if (changed != null) {
			if (changed == Facilities.FULL) {
				news(v, NewsType.HOUSING_FULL, String.valueOf(beds.size()));
			} else {
				news(v, NewsType.HOUSING_FREE, String.valueOf(free));
			}
		}
	}

	/** Tableaux disparus sans qu'un joueur les casse (/setblock, autre mod…). */
	private void validateBoards(ServerLevel level, String dimension) {
		for (Village v : List.copyOf(villages.values())) {
			BlockPos pos = v.boardPos();
			if (!v.boardMissing && v.dimension.equals(dimension) && level.isLoaded(pos)
					&& !level.getBlockState(pos).is(ModBlocks.TOWN_BOARD)) {
				boardRemoved(v, null);
			}
		}
	}

	/** Retire les bornes disparues sans qu'un joueur les casse (explosion de TNT, /setblock…). */
	private void validateBornes(ServerLevel level, String dimension) {
		boolean changed = false;
		for (Village v : villages.values()) {
			if (!v.dimension.equals(dimension)) {
				continue;
			}
			boolean removed = v.bornes.removeIf(packed -> {
				BlockPos pos = BlockPos.of(packed);
				return level.isLoaded(pos) && !level.getBlockState(pos).is(ModBlocks.BOUNDARY_STONE);
			});
			if (removed) {
				v.bornesChanged();
				changed = true;
			}
		}
		if (changed) {
			broadcastBorders();
		}
	}

	/**
	 * Met à jour la fiche d'un villageois vu dans le monde : l'inscrit s'il est sur un territoire,
	 * gère les déménagements, et les départs (hors du territoire depuis {@code leaveDelaySeconds}).
	 *
	 * @return la fiche, ou null s'il n'appartient à aucun village
	 */
	VillagerRecord observe(Villager villager, boolean announce) {
		UUID uuid = villager.getUUID();
		String dimension = dim(villager.level());
		Village current = index.get(uuid);
		if (current == null) {
			Village inside = villageAt(dimension, villager.getX(), villager.getZ());
			if (inside == null) {
				return null;
			}
			VillagerRecord r = register(inside, villager);
			if (announce) {
				news(inside, NewsType.ARRIVAL, r.displayName());
			}
			return r;
		}
		VillagerRecord r = current.villagers.get(uuid.toString());
		if (current.contains(dimension, villager.getX(), villager.getZ(), config.defaultRadius)) {
			r.outsideSince = 0;
		} else {
			Village inside = villageAt(dimension, villager.getX(), villager.getZ());
			if (inside != null) {
				current.villagers.remove(uuid.toString());
				current.dirty = true;
				inside.villagers.put(uuid.toString(), r);
				index.put(uuid, inside);
				r.outsideSince = 0;
				news(current, NewsType.MOVED_OUT, r.displayName(), inside.name);
				news(inside, NewsType.MOVED_IN, r.displayName(), current.name);
			} else if (r.outsideSince == 0) {
				r.outsideSince = System.currentTimeMillis();
			} else if (announce && r.boundSite == null && r.boundHome == null
					&& System.currentTimeMillis() - r.outsideSince >= config.leaveDelaySeconds * 1000L) {
				refresh(r, villager);
				news(current, NewsType.LEFT, r.displayName(), r.baby ? "child" : r.profession);
				genealogy.fate(uuid, Kin.Fate.LEFT, currentDay());
				forget(uuid);
				return null;
			}
		}
		refresh(r, villager);
		return r;
	}

	private VillagerRecord register(Village village, Villager villager) {
		VillagerRecord r = new VillagerRecord();
		r.uuid = villager.getUUID().toString();
		r.firstSeen = System.currentTimeMillis();
		r.firstSeenDay = currentDay();
		village.villagers.put(r.uuid, r);
		index.put(villager.getUUID(), village);
		refresh(r, villager);
		return r;
	}

	@SuppressWarnings("deprecation")
	void refresh(VillagerRecord r, Villager villager) {
		if (villager.hasAttached(Attachments.LEGACY_NAME)) {
			villager.removeAttached(Attachments.LEGACY_NAME);
		}
		Component custom = villager.getCustomName();
		r.customName = custom == null ? null : custom.getString();
		r.profession = Professions.key(villager);
		r.type = Professions.type(villager);
		r.level = villager.getVillagerData().level();
		r.baby = villager.isBaby();
		r.locked = isLocked(villager);
		r.home = villager.getBrain().getMemory(MemoryModuleType.HOME).filter(this::bedExists).map(h -> h.pos().asLong()).orElse(null);
		GlobalPos site = villager.getAttached(Attachments.BOUND_SITE);
		r.boundSite = site == null ? null : site.pos().asLong();
		GlobalPos boundHome = villager.getAttached(Attachments.BOUND_HOME);
		r.boundHome = boundHome == null ? null : boundHome.pos().asLong();
		r.dimension = dim(villager.level());
		r.x = villager.getX();
		r.y = villager.getY();
		r.z = villager.getZ();
		r.lastSeen = System.currentTimeMillis();
		Village v = index.get(villager.getUUID());
		if (v != null) {
			v.dirty = true;
			genealogy.update(r, v.id);
		}
	}

	/**
	 * Le lit mémorisé existe-t-il encore ? Minecraft n'efface la mémoire d'un lit détruit qu'au coucher :
	 * on vérifie donc nous-mêmes, pour qu'un villageois dont le lit a été cassé apparaisse sans abri tout de suite.
	 */
	private boolean bedExists(GlobalPos home) {
		ServerLevel level = server.getLevel(home.dimension());
		return level != null && (!level.isLoaded(home.pos())
				|| level.getPoiManager().exists(home.pos(), type -> type.is(PoiTypes.HOME)));
	}

	void forget(UUID uuid) {
		Village v = index.remove(uuid);
		if (v != null) {
			v.villagers.remove(uuid.toString());
			v.dirty = true;
		}
	}

	void news(Village village, NewsType type, String... args) {
		if (village == null) {
			return;
		}
		village.news.addFirst(new NewsEntry(System.currentTimeMillis(), currentDay(), type, List.of(args)));
		while (village.news.size() > config.maxNews) {
			village.news.removeLast();
		}
		village.dirty = true;
	}

	long currentDay() {
		return server.overworld().getOverworldClockTime() / 24000 + 1;
	}

	// ------------------------------------------------------------------ événements de jeu

	/** Parents d'un bébé : leurs noms pour la gazette (les anonymes ne sont pas cités) et leurs UUID pour l'état civil. */
	private record Pending(String names, List<UUID> parents) {
	}

	/** Naissance : on note les parents, pour la gazette et l'état civil. */
	public void onBreed(Villager child, Villager parent, Villager partner) {
		if (pendingParents.size() > 64) {
			pendingParents.clear();
		}
		String names = Stream.of(displayName(parent), displayName(partner))
				.filter(n -> !n.isEmpty())
				.collect(Collectors.joining(" & "));
		for (Villager p : List.of(parent, partner)) {
			Village home = index.get(p.getUUID());
			genealogy.touch(p.getUUID(), displayName(p), Professions.key(p), Professions.type(p), p.isBaby(), home == null ? null : home.id);
		}
		// Deux célibataires qui ont un enfant sont mariés d'office.
		if (genealogy.spouse(parent.getUUID()) == null && genealogy.spouse(partner.getUUID()) == null) {
			genealogy.marry(parent.getUUID(), partner.getUUID(), currentDay());
			Village home = index.containsKey(parent.getUUID()) ? index.get(parent.getUUID())
					: villageAt(dim(parent.level()), parent.getX(), parent.getZ());
			news(home, NewsType.MARRIED, displayName(parent), displayName(partner), "child");
		}
		pendingParents.put(child.getUUID(), new Pending(names, List.of(parent.getUUID(), partner.getUUID())));
	}

	void onEntityLoad(Villager villager) {
		Pending pending = pendingParents.remove(villager.getUUID());
		if (pending == null) {
			observe(villager, true);
			return;
		}
		Village village = villageAt(dim(villager.level()), villager.getX(), villager.getZ());
		genealogy.birth(villager.getUUID(), pending.parents(), displayName(villager), Professions.type(villager),
				village == null ? null : village.id, currentDay());
		if (village == null || index.containsKey(villager.getUUID())) {
			return;
		}
		VillagerRecord r = register(village, villager);
		r.born = true;
		r.parents = pending.names();
		news(village, NewsType.BIRTH, r.displayName(), pending.names());
	}

	void onEntityUnload(Villager villager) {
		VillagerRecord r = record(villager.getUUID());
		if (r != null && villager.isAlive()) {
			refresh(r, villager);
		}
	}

	void onDeath(Villager villager, DamageSource source) {
		VillagerRecord r = observe(villager, false);
		genealogy.fate(villager.getUUID(), Kin.Fate.DEAD, currentDay());
		if (r == null) {
			return;
		}
		Village village = index.get(villager.getUUID());
		news(village, NewsType.DEATH, r.displayName(), r.baby ? "child" : r.profession, cause(source));
		forget(villager.getUUID());
	}

	void onConvertedFrom(Villager villager, EntityType<?> into) {
		genealogy.fate(villager.getUUID(), into == EntityTypes.WITCH ? Kin.Fate.WITCH : Kin.Fate.ZOMBIFIED, currentDay());
		Village village = index.containsKey(villager.getUUID())
				? index.get(villager.getUUID())
				: villageAt(dim(villager.level()), villager.getX(), villager.getZ());
		if (village == null) {
			return;
		}
		news(village, into == EntityTypes.WITCH ? NewsType.WITCH : NewsType.ZOMBIFIED, displayName(villager));
		forget(villager.getUUID());
	}

	void onCured(Villager villager) {
		Village village = villageAt(dim(villager.level()), villager.getX(), villager.getZ());
		if (village == null) {
			return;
		}
		VillagerRecord r = index.containsKey(villager.getUUID()) ? record(villager.getUUID()) : register(village, villager);
		news(village, NewsType.CURED, r.displayName());
	}

	/** Appelé (par le mixin) juste avant un changement de métier. */
	public void onCareerChange(Villager villager, String oldProfession, String newProfession) {
		VillagerRecord r = observe(villager, true);
		if (r == null) {
			return;
		}
		r.profession = newProfession;
		if (bypassFreeze) {
			return; // affectation par contrat : la gazette annonce le contrat lui-même
		}
		Village village = index.get(villager.getUUID());
		if (newProfession.equals(Professions.NONE)) {
			news(village, NewsType.JOB_LOST, r.displayName(), oldProfession);
		} else {
			news(village, NewsType.JOB, r.displayName(), newProfession);
		}
	}

	/** Encodage « player:Nom », « entity:clé.de.traduction » ou « damage:type » ; le client le traduit. */
	private static String cause(DamageSource source) {
		Entity killer = source.getEntity();
		if (killer instanceof Player player) {
			return "player:" + player.getPlainTextName();
		}
		if (killer != null) {
			return "entity:" + killer.getType().getDescriptionId();
		}
		return "damage:" + source.typeHolder().unwrapKey().map(k -> k.identifier().getPath()).orElse(source.getMsgId());
	}

	// ------------------------------------------------------------------ nom, verrou, liaison

	/** Le nom donné par un joueur, ou chaîne vide pour un villageois sans nom. */
	String displayName(Villager villager) {
		Component custom = villager.getCustomName();
		return custom == null ? "" : custom.getString();
	}

	/** « Côme », ou « un villageois sans nom » (milieu de phrase). */
	static Component who(String name) {
		return name.isEmpty() ? Component.translatable("villageboard.someone") : Component.literal(name);
	}

	/** « Côme », ou « Un villageois sans nom » (début de phrase). */
	static Component whoCap(String name) {
		return name.isEmpty() ? Component.translatable("villageboard.someone.cap") : Component.literal(name);
	}

	public static boolean isLocked(Villager villager) {
		return Boolean.TRUE.equals(villager.getAttached(Attachments.LOCKED));
	}

	/** Métier figé : verrouillé, ou lié à un poste par un contrat. */
	public boolean isFrozen(Villager villager) {
		return !bypassFreeze && (isLocked(villager) || Assignments.isBound(villager, Assignments.Kind.WORK));
	}

	void setLocked(Villager villager, boolean locked) {
		if (locked) {
			villager.setAttached(Attachments.LOCKED, true);
		} else {
			villager.removeAttached(Attachments.LOCKED);
		}
		VillagerRecord r = record(villager.getUUID());
		if (r != null) {
			refresh(r, villager);
		}
	}

	static String clean(String input) {
		String s = input.replaceAll("[\\p{Cntrl}§]", "").trim();
		return s.length() > 32 ? s.substring(0, 32) : s;
	}

	private static void message(ServerPlayer player, Component message) {
		if (player != null) {
			player.sendSystemMessage(message);
		}
	}
}
