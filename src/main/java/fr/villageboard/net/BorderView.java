package fr.villageboard.net;

import fr.villageboard.village.Territory;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

import java.util.List;

/** Limites d'un village envoyées à tous les clients (affichage des frontières, message d'entrée). */
public record BorderView(String id, String name, String dimension, BlockPos board, int radius, List<BlockPos> polygon) {

	public boolean contains(String dim, double x, double z) {
		return dimension.equals(dim) && Territory.contains(polygon, board, radius, x, z);
	}

	void write(FriendlyByteBuf buf) {
		buf.writeUtf(id);
		buf.writeUtf(name);
		buf.writeUtf(dimension);
		buf.writeBlockPos(board);
		buf.writeVarInt(radius);
		buf.writeCollection(polygon, (b, p) -> b.writeBlockPos(p));
	}

	static BorderView read(FriendlyByteBuf buf) {
		return new BorderView(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readBlockPos(), buf.readVarInt(),
				buf.readList(b -> b.readBlockPos()));
	}
}
