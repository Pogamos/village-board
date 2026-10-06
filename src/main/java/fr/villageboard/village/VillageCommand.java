package fr.villageboard.village;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * /villageboard list — liste les villages
 * /villageboard info &lt;id&gt; — équipements et population (lits, postes, cloches, golems)
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
			ctx.getSource().sendSuccess(() -> Component.translatable("villageboard.cmd.entry",
					v.name, v.id, v.villagers.size(), v.bornes.size(), v.boardPos().toShortString(), v.dimension), false);
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
