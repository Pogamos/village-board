package fr.villageboard.village;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Géométrie d'un territoire, partagée par le serveur et le client.
 * Avec 3 bornes ou plus : polygone dont les sommets sont triés par angle autour de leur barycentre
 * (convient à toute forme « étoilée », c'est-à-dire sans renfoncement très marqué).
 * Avec moins de 3 bornes : cercle provisoire autour du tableau.
 */
public final class Territory {

	private Territory() {
	}

	public static List<BlockPos> order(List<BlockPos> bornes) {
		if (bornes.size() < 3) {
			return List.copyOf(bornes);
		}
		double cx = bornes.stream().mapToDouble(BlockPos::getX).average().orElse(0);
		double cz = bornes.stream().mapToDouble(BlockPos::getZ).average().orElse(0);
		List<BlockPos> sorted = new ArrayList<>(bornes);
		sorted.sort(Comparator.comparingDouble(p -> Math.atan2(p.getZ() - cz, p.getX() - cx)));
		return sorted;
	}

	/** {@code polygon} doit déjà être ordonné (voir {@link #order}). */
	public static boolean contains(List<BlockPos> polygon, BlockPos board, int radius, double x, double z) {
		if (polygon.size() < 3) {
			double dx = x - (board.getX() + 0.5);
			double dz = z - (board.getZ() + 0.5);
			return dx * dx + dz * dz <= (double) radius * radius;
		}
		boolean inside = false;
		for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
			double xi = polygon.get(i).getX() + 0.5;
			double zi = polygon.get(i).getZ() + 0.5;
			double xj = polygon.get(j).getX() + 0.5;
			double zj = polygon.get(j).getZ() + 0.5;
			if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) {
				inside = !inside;
			}
		}
		return inside;
	}

	/** Superficie approximative en blocs². */
	public static long area(List<BlockPos> polygon, int radius) {
		if (polygon.size() < 3) {
			return Math.round(Math.PI * radius * radius);
		}
		double sum = 0;
		for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
			sum += (double) polygon.get(j).getX() * polygon.get(i).getZ() - (double) polygon.get(i).getX() * polygon.get(j).getZ();
		}
		return Math.round(Math.abs(sum) / 2);
	}
}
