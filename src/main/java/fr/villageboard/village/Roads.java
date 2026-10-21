package fr.villageboard.village;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import fr.villageboard.VillageBoard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * Chemins du village : un réseau de carrefours et de passages, appris en regardant les villageois marcher.
 * <p>
 * Un villageois ne calcule son chemin que sur 48 blocs, et en explorant au plus 768 positions : dans un grand village
 * ou un village dense, il n'atteint pas son lit ou son poste, avance au hasard vers lui et finit par l'oublier (au bout
 * d'une minute sans chemin). Ici, chaque villageois est relevé une fois par seconde : l'endroit où il se tient devient
 * un carrefour (ou rejoint le carrefour le plus proche en vue directe), et le pas qu'il vient de faire, un passage
 * de l'un à l'autre. Les rues fréquentées se dessinent d'elles-mêmes, sans aucun calcul de chemin.
 * <p>
 * Pour une destination lointaine, on cherche l'itinéraire sur ce réseau (quelques centaines de carrefours, mis en
 * cache et partagé entre villageois), et le villageois marche d'étape en étape : Minecraft n'a plus qu'à calculer des
 * chemins courts, moins coûteux que le chemin complet qu'il aurait tenté. Les passages fréquentés coûtent moins cher :
 * les villageois finissent par emprunter les mêmes rues. Un passage qui ne mène plus nulle part (mur construit depuis)
 * est oublié après quelques échecs ; un passage que personne n'a emprunté depuis une semaine de jeu aussi.
 */
final class Roads {

	/** Intervalle entre deux relevés d'un même villageois (ticks). */
	static final int SAMPLE_TICKS = 20;
	/** Côté d'une case de l'index spatial (blocs). */
	private static final int CELL = 4;
	/** Un carrefour représente les positions à moins de 3 blocs de lui, en vue directe. */
	private static final double SNAP = 3;
	/** Un pas plus long entre deux relevés n'est pas un passage (chute, poussée, téléportation). */
	private static final double MAX_STEP = 9;
	private static final int MAX_STEP_HEIGHT = 4;
	/** Carrefours voisins visibles reliés d'office à la création d'un carrefour. */
	private static final double SIGHT_LINK = 8;
	/** En deçà, Minecraft trouve seul son chemin. */
	private static final double ROUTE_MIN = 24;
	/** Distance de marche jusqu'à l'étape suivante. */
	private static final double LOOKAHEAD = 20;
	private static final double ENTRY_RADIUS = 10;
	private static final double EXIT_RADIUS = 12;
	private static final int MAX_NODES = 6000;
	private static final int MAX_EXPANSIONS = 5000;
	/** Itinéraires calculés au plus par tick, tous villages confondus ; au-delà, Minecraft s'en charge seul. */
	private static final int ROUTES_PER_TICK = 16;
	private static final long ROUTE_TTL = 600;
	private static final int MAX_CACHED_ROUTES = 4000;
	/** Un passage ou un carrefour inutilisé pendant une semaine de jeu est oublié. */
	private static final long FORGET_AFTER = 7 * 24000L;
	private static final int MAX_FAILURES = 3;
	/** Pause après un échec : le villageois cherche seul pendant ce temps. */
	private static final long FAILURE_PAUSE = 200;
	/**
	 * Sans se rapprocher de sa destination pendant 30 s, ou après 3 étapes inaccessibles, le villageois renonce au réseau
	 * pour cette destination pendant 5 minutes. Minecraft reprend la main : au bout d'une minute sans chemin, il oublie
	 * une destination vraiment inaccessible, comme sans le mod (chaque étape atteinte via le réseau repoussait ce délai).
	 */
	private static final long STALL = 600;
	private static final int TRIP_FAILURES = 3;
	private static final long ABANDON = 6000;
	private static final int SHOW_RADIUS = 24;

	private static final Gson GSON = new Gson();
	private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Saved>>() {
	}.getType();

	private final Path file;
	private final Map<String, Network> networks = new HashMap<>();
	/** Dernier carrefour où chaque villageois a été relevé. */
	private final Map<UUID, Step> lastSteps = new HashMap<>();
	/** Trajet en cours de chaque villageois guidé par le réseau. */
	private final Map<UUID, Trip> trips = new HashMap<>();
	/** Joueurs qui affichent le réseau (/villageboard paths), jusqu'à quel tick. */
	private final Map<UUID, Long> viewers = new HashMap<>();
	private long budgetTick = -1;
	private int budgetUsed;
	private boolean dirty;

	Roads(Path file) {
		this.file = file;
	}

	// ------------------------------------------------------------------ apprentissage

	/** Relevé d'un villageois du village : là où il se tient, et le pas qu'il vient de faire. */
	void observe(ServerLevel level, Villager villager, Village village, boolean inside) {
		UUID id = villager.getUUID();
		if (!inside || villager.isBaby() || villager.isPassenger() || villager.isSleeping() || villager.isInLiquid()) {
			// Un bébé passe sous un bloc de haut, pas un adulte ; dans l'eau ou dans un wagonnet, ce n'est pas un chemin.
			lastSteps.remove(id);
			return;
		}
		if (!villager.onGround()) {
			return; // en plein saut (une marche) : on attend qu'il retombe, le pas compte quand même
		}
		Network net = networks.computeIfAbsent(village.id, k -> new Network());
		long now = level.getGameTime();
		BlockPos pos = villager.blockPosition();
		int known = net.nodes.size();
		Node node = net.snap(level, villager, pos, now);
		dirty |= net.nodes.size() != known;
		Step previous = lastSteps.get(id);
		if (node != null && previous != null && previous.node != node && now - previous.time <= SAMPLE_TICKS * 3L
				&& net.nodes.get(previous.node.id) == previous.node && previous.node.isStep(node)) {
			dirty |= net.link(previous.node, node, now);
		}
		if (node == null) {
			lastSteps.remove(id);
		} else {
			lastSteps.put(id, new Step(node, now));
		}
	}

	// ------------------------------------------------------------------ itinéraire

	/**
	 * Étape suivante vers une destination lointaine, d'après le réseau du village : un carrefour à une vingtaine de blocs
	 * de marche sur l'itinéraire. Null si Minecraft doit chercher seul (destination proche, pas de réseau, pas
	 * d'itinéraire, ou le réseau n'a pas aidé ce villageois récemment).
	 */
	BlockPos next(ServerLevel level, Villager villager, Village village, BlockPos target) {
		Network net = networks.get(village.id);
		if (net == null || net.nodes.size() < 2) {
			return null;
		}
		UUID id = villager.getUUID();
		Trip trip = trips.get(id);
		double distance = Math.sqrt(villager.blockPosition().distSqr(target));
		// Proche en ligne droite, la destination peut être inaccessible (de l'autre côté d'un mur) : on prend le réseau
		// aussi quand Minecraft n'y arrive pas, et on le garde jusqu'au bout d'un trajet commencé.
		boolean guided = trip != null && trip.target.equals(target) && trip.route != null;
		if (distance < ROUTE_MIN && !guided && !villager.getBrain().hasMemoryValue(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE)) {
			return null;
		}
		long now = level.getGameTime();
		if (trip == null || !trip.target.equals(target)) {
			trip = new Trip(target, now);
			trips.put(id, trip);
		} else if (now < trip.pausedUntil) {
			return null;
		}
		trip.lastUse = now;
		trip.route = null;

		// Départ : le carrefour où il vient d'être relevé, sinon le plus proche ; un carrefour d'où ne part aucun passage
		// (un cul-de-sac où il est arrivé avant d'être recensé) ne mène nulle part.
		BlockPos from = villager.blockPosition();
		Step last = lastSteps.get(id);
		Node start;
		if (last != null && net.nodes.get(last.node.id) == last.node && now - last.time <= SAMPLE_TICKS * 3L
				&& !last.node.out.isEmpty() && last.node.pos.distSqr(from) <= ENTRY_RADIUS * ENTRY_RADIUS) {
			start = last.node;
		} else if (from.equals(trip.from) && (trip.start == null || net.nodes.get(trip.start.id) == trip.start)) {
			start = trip.start; // bloqué sur place, Minecraft recalcule sans cesse : inutile de chercher de nouveau
		} else {
			start = net.entry(level, villager, from);
			trip.from = from;
			trip.start = start;
		}
		if (start == null || isGoal(start, target)) {
			return null;
		}
		List<Node> route = net.route(this, start, target, now);
		if (route == null) {
			return null;
		}
		// Progrès mesuré sur l'itinéraire : un détour éloigne d'abord de la destination à vol d'oiseau.
		double remaining = 0;
		for (int i = 1; i < route.size(); i++) {
			remaining += Math.sqrt(route.get(i - 1).pos.distSqr(route.get(i).pos));
		}
		if (remaining < trip.best - 2) {
			trip.best = remaining;
			trip.progressAt = now;
		} else if (now - trip.progressAt > STALL) {
			pause(trip, now, ABANDON); // le réseau ne le rapproche pas
			return null;
		}
		double walked = 0;
		int stepIndex = 0;
		for (int i = 1; i < route.size(); i++) {
			walked += Math.sqrt(route.get(i - 1).pos.distSqr(route.get(i).pos));
			if (walked > LOOKAHEAD) {
				break;
			}
			stepIndex = i;
		}
		if (stepIndex == 0 || stepIndex == route.size() - 1) {
			return null; // la destination est à portée : Minecraft y va directement
		}
		trip.route = route.subList(0, stepIndex + 1);
		return route.get(stepIndex).pos;
	}

	/** Carrefour d'arrivée possible : à moins de {@link #EXIT_RADIUS} blocs de la destination, à peu près au même niveau. */
	private static boolean isGoal(Node node, BlockPos target) {
		int dx = node.pos.getX() - target.getX();
		int dz = node.pos.getZ() - target.getZ();
		return dx * dx + dz * dz <= EXIT_RADIUS * EXIT_RADIUS && Math.abs(node.pos.getY() - target.getY()) <= 2;
	}

	/** L'étape donnée par {@link #next} est inaccessible : ses passages sont suspects, le villageois cherche seul un moment. */
	void failed(ServerLevel level, Villager villager, Village village) {
		Trip trip = trips.get(villager.getUUID());
		Network net = networks.get(village.id);
		if (trip == null || trip.route == null || net == null) {
			return;
		}
		for (int i = 1; i < trip.route.size(); i++) {
			Node from = trip.route.get(i - 1);
			Edge edge = from.out.get(trip.route.get(i).id);
			if (edge != null && ++edge.failures >= MAX_FAILURES) {
				from.out.remove(edge.to.id);
				net.version++;
				dirty = true;
			}
		}
		trip.route = null;
		pause(trip, level.getGameTime(), ++trip.failures >= TRIP_FAILURES ? ABANDON : FAILURE_PAUSE);
	}

	private static void pause(Trip trip, long now, long duration) {
		trip.pausedUntil = now + duration;
		trip.progressAt = trip.pausedUntil;
	}

	/** Budget de calcul d'itinéraires du tick en cours. */
	private boolean spend(long now) {
		if (now != budgetTick) {
			budgetTick = now;
			budgetUsed = 0;
		}
		return budgetUsed++ < ROUTES_PER_TICK;
	}

	// ------------------------------------------------------------------ entretien

	/** Oublie les passages inutilisés, les carrefours isolés, les itinéraires périmés et les réseaux des villages dissous. */
	void prune(VillageManager manager, long now) {
		networks.keySet().removeIf(id -> {
			boolean gone = manager.village(id) == null;
			dirty |= gone;
			return gone;
		});
		for (Network net : networks.values()) {
			dirty |= net.prune(now);
		}
		lastSteps.values().removeIf(s -> now - s.time > SAMPLE_TICKS * 10L);
		trips.values().removeIf(t -> now - t.lastUse > ABANDON && now > t.pausedUntil);
	}

	void forget(UUID villager) {
		lastSteps.remove(villager);
		trips.remove(villager);
	}

	/** Carrefours et passages du village, et passages qui ont échoué (pour /villageboard info). */
	int[] stats(Village village) {
		Network net = networks.get(village.id);
		if (net == null) {
			return new int[3];
		}
		int edges = 0;
		int failing = 0;
		for (Node n : net.nodes.values()) {
			edges += n.out.size();
			for (Edge e : n.out.values()) {
				if (e.failures > 0) {
					failing++;
				}
			}
		}
		return new int[] {net.nodes.size(), edges, failing};
	}

	// ------------------------------------------------------------------ affichage (opérateurs)

	void show(ServerPlayer player, int seconds) {
		viewers.put(player.getUUID(), player.level().getGameTime() + seconds * 20L);
	}

	/**
	 * Dessine le réseau autour des joueurs qui l'ont demandé : carrefours en blanc, passages du jaune (récent)
	 * au vert (fréquenté), en rouge s'ils ont échoué.
	 */
	void tickDisplay(VillageManager manager, Iterable<ServerPlayer> players) {
		if (viewers.isEmpty()) {
			return;
		}
		for (ServerPlayer player : players) {
			Long until = viewers.get(player.getUUID());
			if (until == null) {
				continue;
			}
			ServerLevel level = player.level();
			if (level.getGameTime() > until) {
				viewers.remove(player.getUUID());
				continue;
			}
			Village village = manager.villageAt(VillageManager.dim(level), player.getX(), player.getZ());
			Network net = village == null ? null : networks.get(village.id);
			if (net == null) {
				continue;
			}
			BlockPos center = player.blockPosition();
			for (Node node : net.nodes.values()) {
				if (Math.abs(node.pos.getX() - center.getX()) > SHOW_RADIUS || Math.abs(node.pos.getZ() - center.getZ()) > SHOW_RADIUS) {
					continue;
				}
				particle(level, player, NODE_DUST, Vec3.atBottomCenterOf(node.pos));
				for (Edge edge : node.out.values()) {
					Vec3 a = Vec3.atBottomCenterOf(node.pos);
					Vec3 b = Vec3.atBottomCenterOf(edge.to.pos);
					DustParticleOptions dust = edge.failures > 0 ? FAILED_DUST : edge.uses >= 8 ? BUSY_DUST : NEW_DUST;
					int points = (int) Math.ceil(a.distanceTo(b) * 2);
					// Le point est décalé vers l'arrivée : on distingue A → B de B → A.
					for (int i = 1; i < points; i++) {
						double t = (double) i / points;
						particle(level, player, dust, a.lerp(b, t).add(0, 0.2 + 0.3 * t, 0));
					}
				}
			}
		}
	}

	private static final DustParticleOptions NODE_DUST = new DustParticleOptions(0xFFFFFF, 1.5f);
	private static final DustParticleOptions NEW_DUST = new DustParticleOptions(0xFFD84A, 0.8f);
	private static final DustParticleOptions BUSY_DUST = new DustParticleOptions(0x4AE04A, 0.8f);
	private static final DustParticleOptions FAILED_DUST = new DustParticleOptions(0xE04A4A, 0.8f);

	private static void particle(ServerLevel level, ServerPlayer player, DustParticleOptions dust, Vec3 at) {
		level.sendParticles(player, dust, true, false, at.x, at.y + 0.2, at.z, 1, 0, 0, 0, 0);
	}

	// ------------------------------------------------------------------ persistance

	void load() {
		if (!Files.exists(file)) {
			return;
		}
		try {
			Map<String, Saved> data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), DATA_TYPE);
			if (data != null) {
				data.forEach((id, saved) -> networks.put(id, Network.of(saved)));
			}
		} catch (IOException | RuntimeException e) {
			VillageBoard.LOGGER.error("Impossible de lire {}", file, e);
		}
	}

	void save(boolean force) {
		if (!dirty && !force) {
			return;
		}
		try {
			Map<String, Saved> data = new LinkedHashMap<>();
			networks.forEach((id, net) -> data.put(id, net.saved()));
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(data, DATA_TYPE), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			dirty = false;
		} catch (IOException e) {
			VillageBoard.LOGGER.error("Impossible d'enregistrer {}", file, e);
		}
	}

	/** Réseau d'un village sur disque : carrefours (BlockPos.asLong) et passages [départ, arrivée, passages, dernier tick]. */
	private static final class Saved {
		List<Long> nodes = new ArrayList<>();
		List<Long> seen = new ArrayList<>();
		List<long[]> edges = new ArrayList<>();
	}

	// ------------------------------------------------------------------ réseau

	private record Step(Node node, long time) {
	}

	private static final class Trip {
		final BlockPos target;
		double best;
		long progressAt;
		long pausedUntil;
		long lastUse;
		int failures;
		/** Portion d'itinéraire jusqu'à l'étape donnée (pour noter un échec). */
		List<Node> route;
		/** Dernière position de départ cherchée, et le carrefour trouvé (null : aucun). */
		BlockPos from;
		Node start;

		Trip(BlockPos target, long now) {
			this.target = target;
			this.best = Double.MAX_VALUE;
			this.progressAt = now;
			this.lastUse = now;
		}
	}

	private static final class Node {
		final int id;
		final BlockPos pos;
		long seen;
		/** Passages qui partent de ce carrefour, par identifiant du carrefour d'arrivée. */
		final Map<Integer, Edge> out = new HashMap<>();

		Node(int id, BlockPos pos, long seen) {
			this.id = id;
			this.pos = pos;
			this.seen = seen;
		}

		boolean isStep(Node other) {
			int dx = other.pos.getX() - pos.getX();
			int dz = other.pos.getZ() - pos.getZ();
			return dx * dx + dz * dz <= MAX_STEP * MAX_STEP && Math.abs(other.pos.getY() - pos.getY()) <= MAX_STEP_HEIGHT;
		}
	}

	private static final class Edge {
		final Node to;
		final float length;
		int uses;
		long seen;
		int failures;

		Edge(Node to, float length) {
			this.to = to;
			this.length = length;
		}

		/** Coût de passage : jusqu'au double de la longueur pour un passage neuf, la longueur pour une rue fréquentée. */
		double cost() {
			return length * (1 + 1.0 / (1 + uses));
		}
	}

	private record Route(List<Node> nodes, int version, long expires) {
	}

	private static final class Network {
		final Map<Integer, Node> nodes = new HashMap<>();
		/** Index spatial : carrefours par case de {@link #CELL} blocs. */
		final Map<Long, List<Node>> cells = new HashMap<>();
		final Map<RouteKey, Route> routes = new HashMap<>();
		int nextId;
		/** Augmenté quand un passage disparaît : les itinéraires en cache sont alors recalculés. */
		int version;

		static long cell(int x, int y, int z) {
			return BlockPos.asLong(Math.floorDiv(x, CELL), Math.floorDiv(y, CELL), Math.floorDiv(z, CELL));
		}

		Node add(BlockPos pos, long now) {
			Node node = new Node(nextId++, pos.immutable(), now);
			nodes.put(node.id, node);
			cells.computeIfAbsent(cell(pos.getX(), pos.getY(), pos.getZ()), k -> new ArrayList<>(2)).add(node);
			return node;
		}

		/** Le carrefour qui représente cette position (créé au besoin) ; null si le réseau est plein. */
		Node snap(ServerLevel level, Villager villager, BlockPos pos, long now) {
			List<Node> near = candidates(pos, SNAP, 1);
			for (Node n : near) {
				if (n.pos.distSqr(pos) < 2 || visible(level, villager, pos, n.pos)) {
					n.seen = now;
					return n;
				}
			}
			if (nodes.size() >= MAX_NODES) {
				return null;
			}
			Node node = add(pos, now);
			// Liaisons à vue : un relevé manqué (saut, bousculade) ne doit pas couper une rue en deux. Les carrefours
			// voisins visibles, de même niveau, sont reliés d'office, comme des passages neufs (les plus chers).
			for (Node n : candidates(pos, SIGHT_LINK, 1)) {
				if (n != node && visible(level, villager, pos, n.pos)) {
					sight(node, n, now);
					sight(n, node, now);
				}
			}
			return node;
		}

		private static void sight(Node from, Node to, long now) {
			if (!from.out.containsKey(to.id)) {
				Edge edge = new Edge(to, (float) Math.sqrt(from.pos.distSqr(to.pos)));
				edge.seen = now;
				from.out.put(to.id, edge);
			}
		}

		/** Le carrefour d'où partir : le plus proche d'où part un passage, de préférence en vue directe. */
		Node entry(ServerLevel level, Villager villager, BlockPos pos) {
			List<Node> near = candidates(pos, ENTRY_RADIUS, 2);
			near.removeIf(n -> n.out.isEmpty());
			for (int i = 0; i < Math.min(4, near.size()); i++) {
				if (visible(level, villager, pos, near.get(i).pos)) {
					return near.get(i);
				}
			}
			return near.isEmpty() ? null : near.getFirst();
		}

		/** Carrefours à moins de {@code radius} blocs (à l'horizontale) et {@code maxDy} blocs de haut, du plus proche au plus lointain. */
		private List<Node> candidates(BlockPos pos, double radius, int maxDy) {
			int r = (int) Math.ceil(radius / CELL);
			int cx = Math.floorDiv(pos.getX(), CELL);
			int cy = Math.floorDiv(pos.getY(), CELL);
			int cz = Math.floorDiv(pos.getZ(), CELL);
			List<Node> found = new ArrayList<>();
			for (int x = cx - r; x <= cx + r; x++) {
				for (int z = cz - r; z <= cz + r; z++) {
					for (int y = cy - 1; y <= cy + 1; y++) {
						List<Node> cell = cells.get(BlockPos.asLong(x, y, z));
						if (cell == null) {
							continue;
						}
						for (Node n : cell) {
							int dx = n.pos.getX() - pos.getX();
							int dz = n.pos.getZ() - pos.getZ();
							if (dx * dx + dz * dz <= radius * radius && Math.abs(n.pos.getY() - pos.getY()) <= maxDy) {
								found.add(n);
							}
						}
					}
				}
			}
			found.sort(Comparator.comparingDouble(n -> score(n.pos, pos)));
			return found;
		}

		private static double score(BlockPos a, BlockPos b) {
			double dy = (a.getY() - b.getY()) * 2.0;
			return a.distSqr(new BlockPos(b.getX(), a.getY(), b.getZ())) + dy * dy;
		}

		/** Aucun bloc entre les deux positions, à hauteur de hanches (une clôture, un mur ou une porte fermée coupent la vue). */
		private static boolean visible(ServerLevel level, Villager villager, BlockPos a, BlockPos b) {
			Vec3 from = Vec3.atBottomCenterOf(a).add(0, 1, 0);
			Vec3 to = Vec3.atBottomCenterOf(b).add(0, 1, 0);
			return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, villager))
					.getType() == HitResult.Type.MISS;
		}

		/**
		 * Note un pas de {@code from} à {@code to}. Sur un terrain de même niveau (un bloc d'écart au plus, ce qu'un
		 * villageois remonte en sautant), le passage vaut dans les deux sens ; une descente plus forte, dans un seul.
		 * Vrai si un passage est nouveau.
		 */
		boolean link(Node from, Node to, long now) {
			boolean created = linkOneWay(from, to, now);
			if (Math.abs(from.pos.getY() - to.pos.getY()) <= 1) {
				created |= linkOneWay(to, from, now);
			}
			return created;
		}

		private boolean linkOneWay(Node from, Node to, long now) {
			Edge edge = from.out.get(to.id);
			boolean created = edge == null;
			if (created) {
				edge = new Edge(to, (float) Math.sqrt(from.pos.distSqr(to.pos)));
				from.out.put(to.id, edge);
			}
			edge.uses = Math.min(edge.uses + 1, 1000);
			edge.seen = now;
			edge.failures = 0;
			return created;
		}

		/**
		 * Itinéraire de {@code start} jusqu'à un carrefour proche de la destination (A*), depuis le cache s'il est récent ;
		 * null s'il n'y en a pas. Le cache est partagé : tous les villageois qui partent du même carrefour vers la même
		 * destination (un même lit, une même cloche) reprennent le même itinéraire.
		 */
		List<Node> route(Roads roads, Node start, BlockPos target, long now) {
			RouteKey key = new RouteKey(start.id, target.asLong());
			Route cached = routes.get(key);
			if (cached != null && cached.version == version && now < cached.expires) {
				return cached.nodes;
			}
			if (!roads.spend(now)) {
				return null;
			}
			List<Node> found = search(start, target);
			if (routes.size() >= MAX_CACHED_ROUTES) {
				routes.clear();
			}
			// On garde aussi l'absence d'itinéraire : inutile de chercher de nouveau avant expiration.
			routes.put(key, new Route(found, version, now + ROUTE_TTL));
			return found;
		}

		private List<Node> search(Node start, BlockPos target) {
			Map<Integer, Double> cost = new HashMap<>();
			Map<Integer, Node> cameFrom = new HashMap<>();
			PriorityQueue<Open> open = new PriorityQueue<>(Comparator.comparingDouble(Open::f));
			cost.put(start.id, 0.0);
			open.add(new Open(start, heuristic(start, target)));
			int expansions = 0;
			while (!open.isEmpty() && expansions++ < MAX_EXPANSIONS) {
				Open current = open.poll();
				Node node = current.node;
				double base = cost.get(node.id);
				if (current.f > base + heuristic(node, target) + 1e-6) {
					continue; // entrée périmée de la file
				}
				if (isGoal(node, target)) {
					List<Node> route = new ArrayList<>();
					for (Node n = node; n != null; n = cameFrom.get(n.id)) {
						route.add(n);
					}
					Collections.reverse(route);
					return route;
				}
				for (Edge edge : node.out.values()) {
					double g = base + edge.cost();
					Double known = cost.get(edge.to.id);
					if (known == null || g < known) {
						cost.put(edge.to.id, g);
						cameFrom.put(edge.to.id, node);
						open.add(new Open(edge.to, g + heuristic(edge.to, target)));
					}
				}
			}
			return null;
		}

		/** Distance minimale restante jusqu'à un carrefour d'arrivée (n'en surestime jamais le coût). */
		private static double heuristic(Node node, BlockPos target) {
			return Math.max(0, Math.sqrt(node.pos.distSqr(target)) - EXIT_RADIUS);
		}

		private record RouteKey(int start, long target) {
		}

		private record Open(Node node, double f) {
		}

		/** Oublie ce qui n'a pas servi depuis {@link #FORGET_AFTER}. Vrai si quelque chose a changé. */
		boolean prune(long now) {
			boolean changed = false;
			Map<Integer, Integer> incoming = new HashMap<>();
			for (Node n : nodes.values()) {
				Iterator<Edge> it = n.out.values().iterator();
				while (it.hasNext()) {
					Edge e = it.next();
					if (now - e.seen > FORGET_AFTER || nodes.get(e.to.id) != e.to) {
						it.remove();
						changed = true;
					} else {
						incoming.merge(e.to.id, 1, Integer::sum);
					}
				}
			}
			Iterator<Node> it = nodes.values().iterator();
			while (it.hasNext()) {
				Node n = it.next();
				if (n.out.isEmpty() && !incoming.containsKey(n.id) && now - n.seen > FORGET_AFTER) {
					it.remove();
					List<Node> cell = cells.get(cell(n.pos.getX(), n.pos.getY(), n.pos.getZ()));
					if (cell != null) {
						cell.remove(n);
						if (cell.isEmpty()) {
							cells.remove(cell(n.pos.getX(), n.pos.getY(), n.pos.getZ()));
						}
					}
					changed = true;
				}
			}
			if (changed) {
				version++;
			}
			routes.values().removeIf(r -> now >= r.expires || r.version != version);
			return changed;
		}

		Saved saved() {
			Saved s = new Saved();
			Map<Integer, Integer> index = new HashMap<>();
			for (Node n : nodes.values()) {
				index.put(n.id, s.nodes.size());
				s.nodes.add(n.pos.asLong());
				s.seen.add(n.seen);
			}
			for (Node n : nodes.values()) {
				for (Edge e : n.out.values()) {
					Integer to = index.get(e.to.id);
					if (to != null) {
						s.edges.add(new long[] {index.get(n.id), to, e.uses, e.seen});
					}
				}
			}
			return s;
		}

		static Network of(Saved s) {
			Network net = new Network();
			List<Node> byIndex = new ArrayList<>();
			for (int i = 0; i < s.nodes.size(); i++) {
				long seen = s.seen != null && i < s.seen.size() ? s.seen.get(i) : 0;
				byIndex.add(net.add(BlockPos.of(s.nodes.get(i)), seen));
			}
			for (long[] e : s.edges) {
				if (e.length < 4 || e[0] < 0 || e[1] < 0 || e[0] >= byIndex.size() || e[1] >= byIndex.size()) {
					continue;
				}
				Node from = byIndex.get((int) e[0]);
				Node to = byIndex.get((int) e[1]);
				Edge edge = new Edge(to, (float) Math.sqrt(from.pos.distSqr(to.pos)));
				edge.uses = (int) e[2];
				edge.seen = e[3];
				from.out.put(to.id, edge);
			}
			return net;
		}
	}
}
