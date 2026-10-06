package fr.villageboard.village;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * /villageboard list — liste les villages
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
