package fr.villageboard.village;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * /villageboard list — liste les villages
 * /villageboard info &lt;id&gt; — équipements et population (lits, postes, cloches, golems), chemins appris
 * /villageboard paths — affiche une minute les chemins du village où l'on se trouve (voir {@link Roads})
 * /villageboard remove &lt;id&gt; — dissout un village (le tableau reste en place)
 * Réservé aux opérateurs : tout le reste se fait en jeu, au tableau et avec les bornes.
 */
public final class VillageCommand {

	private VillageCommand() {
	}

	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
				Commands.literal("villageboard")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.literal("list").executes(VillageCommand::list))
						.then(Commands.literal("info")
								.then(Commands.argument("id", StringArgumentType.word()).executes(VillageCommand::info)))
						.then(Commands.literal("paths").executes(VillageCommand::paths))
						.then(Commands.literal("debugreach")
								.then(Commands.argument("prefix", StringArgumentType.word())
										.then(Commands.argument("x", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
												.then(Commands.argument("y", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
														.then(Commands.argument("z", com.mojang.brigadier.arguments.IntegerArgumentType.integer()).executes(ctx -> {
															String prefix = StringArgumentType.getString(ctx, "prefix");
															net.minecraft.core.BlockPos to = new net.minecraft.core.BlockPos(
																	com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "x"),
																	com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "y"),
																	com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "z"));
															for (net.minecraft.server.level.ServerLevel level : ctx.getSource().getServer().getAllLevels()) {
																for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
																	if (e instanceof net.minecraft.world.entity.npc.villager.Villager v && v.getStringUUID().startsWith(prefix)) {
																		v.getNavigation().stop();
																		var p = FarBeds.longPath(v, to, 2, 220, 64);
																		String r = p == null ? "null" : (p.canReach() ? "ATTEINT " : "PARTIEL ") + p.getNodeCount()
																				+ " nœuds, fin " + p.getEndNode().asBlockPos().toShortString() + " reste " + (int) p.getDistToTarget();
																		ctx.getSource().sendSuccess(() -> Component.literal(prefix + " " + v.blockPosition().toShortString() + " -> " + to.toShortString() + " : " + r), false);
																	}
																}
															}
															return 1;
														}))))))
						.then(Commands.literal("debugblocks")
								.then(Commands.argument("x", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
										.then(Commands.argument("y", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
												.then(Commands.argument("z", com.mojang.brigadier.arguments.IntegerArgumentType.integer()).executes(ctx -> {
													net.minecraft.core.BlockPos c = new net.minecraft.core.BlockPos(
															com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "x"),
															com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "y"),
															com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "z"));
													var level = ctx.getSource().getServer().overworld();
													for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(c.offset(-2, -1, -2), c.offset(2, 2, 2))) {
														var s = level.getBlockState(p);
														if (!s.isAir()) {
															fr.villageboard.VillageBoard.LOGGER.info("[blocks] {} {}", p.toShortString(), s);
														}
													}
													ctx.getSource().sendSuccess(() -> Component.literal("ok"), false);
													return 1;
												})))))
						.then(Commands.literal("debug")
								.then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> {
									Village v = VillageManager.get().village(StringArgumentType.getString(ctx, "id"));
									VillageManager.get().debugVillage(v).forEach(l -> fr.villageboard.VillageBoard.LOGGER.info("[debug] {}", l));
									ctx.getSource().sendSuccess(() -> Component.literal("ok"), false);
									return 1;
								})))
						.then(Commands.literal("remove")
								.then(Commands.argument("id", StringArgumentType.word()).executes(VillageCommand::remove)))));
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		VillageManager m = VillageManager.get();
		if (m == null || m.villages().isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.translatable("villageboard.cmd.none"), false);
			return 0;
		}
		for (Village v : m.villages()) {
			Component entry = Component.translatable("villageboard.cmd.entry",
					v.name, v.id, v.villagers.size(), v.bornes.size(), v.boardPos().toShortString(), v.dimension);
			ctx.getSource().sendSuccess(() -> v.boardMissing
					? entry.copy().append(Component.translatable("villageboard.cmd.no_board"))
					: entry, false);
		}
		return m.villages().size();
	}

	private static int info(CommandContext<CommandSourceStack> ctx) {
		VillageManager m = VillageManager.get();
		String id = StringArgumentType.getString(ctx, "id");
		Village v = m == null ? null : m.village(id);
		if (v == null) {
			ctx.getSource().sendFailure(Component.translatable("villageboard.cmd.unknown", id));
			return 0;
		}
		List<Facilities.Bed> beds = Facilities.beds(v);
		List<Facilities.Workstation> stations = Facilities.workstations(v);
		long freeBeds = beds.stream().filter(b -> !b.occupied()).count();
		long freeStations = stations.stream().filter(w -> !w.occupied()).count();
		long homeless = v.villagers.values().stream().filter(r -> r.home == null).count();
		long unemployed = v.villagers.values().stream().filter(r -> !r.baby && r.profession.equals(Professions.NONE)).count();
		String byJob = stations.stream()
				.collect(Collectors.groupingBy(w -> w.profession().replace("minecraft:", ""), TreeMap::new, Collectors.counting()))
				.toString();
		ctx.getSource().sendSuccess(() -> Component.translatable("villageboard.cmd.info",
				v.name, v.villagers.size(), homeless, unemployed, beds.size(), freeBeds,
				stations.size(), freeStations, byJob, Facilities.bells(v), v.golems), false);
		int[] paths = m.roads().stats(v);
		ctx.getSource().sendSuccess(() -> Component.translatable("villageboard.cmd.paths_info", paths[0], paths[1], paths[2]), false);
		return 1;
	}

	/** Affiche pendant une minute, autour du joueur, le réseau de chemins du village où il se trouve. */
	private static int paths(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		VillageManager m = VillageManager.get();
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		if (m == null || m.villageAt(VillageManager.dim(player.level()), player.getX(), player.getZ()) == null) {
			ctx.getSource().sendFailure(Component.translatable("villageboard.cmd.paths_none"));
			return 0;
		}
		m.roads().show(player, 60);
		ctx.getSource().sendSuccess(() -> Component.translatable("villageboard.cmd.paths_shown"), false);
		return 1;
	}

	private static int remove(CommandContext<CommandSourceStack> ctx) {
		VillageManager m = VillageManager.get();
		String id = StringArgumentType.getString(ctx, "id");
		Village v = m == null ? null : m.village(id);
		if (v == null) {
			ctx.getSource().sendFailure(Component.translatable("villageboard.cmd.unknown", id));
			return 0;
		}
		m.dissolve(v);
		ctx.getSource().sendSuccess(() -> Component.translatable("villageboard.msg.dissolved", v.name), true);
		return 1;
	}
}
