package fr.villageboard.client;

import fr.villageboard.block.ModBlocks;
import fr.villageboard.net.BorderView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Frontières des villages côté client : particules le long des limites (quand on tient une borne ou
 * un tableau, ou si l'option est activée depuis le tableau) et message en entrant / sortant d'un village.
 */
public final class BorderDisplay {

	private static final double VIEW_DISTANCE = 40;

	private static List<BorderView> borders = List.of();
	private static boolean showAlways;
	private static String currentId;
	private static String currentName;
	private static int ticks;

	private BorderDisplay() {
	}

	static void setBorders(List<BorderView> list) {
		borders = list;
	}

	static List<BorderView> borders() {
		return borders;
	}

	public static boolean showAlways() {
		return showAlways;
	}

	public static void toggleShowAlways() {
		showAlways = !showAlways;
	}

	static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null) {
			currentId = null;
			ticks = 0;
			return;
		}
		ticks++;
		String dim = level.dimension().identifier().toString();

		BorderView inside = borders.stream().filter(b -> b.contains(dim, player.getX(), player.getZ())).findFirst().orElse(null);
		String id = inside == null ? null : inside.id();
		if (!Objects.equals(id, currentId)) {
			// Pas de message à la connexion : seulement quand on franchit réellement une limite.
			if (ticks > 40) {
				player.sendOverlayMessage(inside != null
						? Component.translatable("villageboard.border.enter", inside.name())
						: Component.translatable("villageboard.border.leave", currentName));
			}
			currentId = id;
			currentName = inside == null ? null : inside.name();
		}

		boolean holding = player.getMainHandItem().is(ModBlocks.BOUNDARY_STONE.asItem())
				|| player.getOffhandItem().is(ModBlocks.BOUNDARY_STONE.asItem())
				|| player.getMainHandItem().is(ModBlocks.TOWN_BOARD.asItem());
		if ((showAlways || holding) && ticks % 4 == 0) {
			for (BorderView border : borders) {
				if (border.dimension().equals(dim)) {
					drawBorder(level, player, border);
				}
			}
		}
	}

	private static void drawBorder(ClientLevel level, LocalPlayer player, BorderView border) {
		List<BlockPos> polygon = border.polygon();
		if (polygon.size() >= 3) {
			for (int i = 0; i < polygon.size(); i++) {
				BlockPos a = polygon.get(i);
				BlockPos b = polygon.get((i + 1) % polygon.size());
				drawSegment(level, player, a.getX() + 0.5, a.getZ() + 0.5, b.getX() + 0.5, b.getZ() + 0.5);
			}
			ThreadLocalRandom random = ThreadLocalRandom.current();
			for (BlockPos p : polygon) {
				if (horizontalDistSq(player, p.getX() + 0.5, p.getZ() + 0.5) < VIEW_DISTANCE * VIEW_DISTANCE) {
					level.addParticle(ParticleTypes.END_ROD, p.getX() + 0.5, p.getY() + 1.2 + random.nextDouble() * 2.5,
							p.getZ() + 0.5, 0, 0.02, 0);
				}
			}
		} else {
			double cx = border.board().getX() + 0.5;
			double cz = border.board().getZ() + 0.5;
			int steps = Math.max(16, (int) (Mth.TWO_PI * border.radius()));
			for (int s = 0; s < steps; s++) {
				double angle = Mth.TWO_PI * s / steps;
				spawnAt(level, player, cx + Math.cos(angle) * border.radius(), cz + Math.sin(angle) * border.radius());
			}
		}
	}

	private static void drawSegment(ClientLevel level, LocalPlayer player, double x1, double z1, double x2, double z2) {
		double length = Math.hypot(x2 - x1, z2 - z1);
		int steps = Math.max(1, (int) Math.ceil(length));
		for (int s = 0; s <= steps; s++) {
			double t = (double) s / steps;
			spawnAt(level, player, x1 + (x2 - x1) * t, z1 + (z2 - z1) * t);
		}
	}

	private static void spawnAt(ClientLevel level, LocalPlayer player, double x, double z) {
		if (horizontalDistSq(player, x, z) > VIEW_DISTANCE * VIEW_DISTANCE || ThreadLocalRandom.current().nextFloat() > 0.6f) {
			return;
		}
		double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z)) + 0.2;
		level.addParticle(ParticleTypes.HAPPY_VILLAGER, x, y, z, 0, 0, 0);
	}

	private static double horizontalDistSq(LocalPlayer player, double x, double z) {
		double dx = player.getX() - x;
		double dz = player.getZ() - z;
		return dx * dx + dz * dz;
	}
}
