package fr.villageboard.net;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Listes dans un paquet : la taille en VarInt puis chaque élément. Remplace {@code writeCollection} et
 * {@code readList}, retirés de {@link FriendlyByteBuf} en 26.3.
 */
public final class Lists {

	private Lists() {
	}

	public static <T> void write(FriendlyByteBuf buf, Collection<T> values, BiConsumer<FriendlyByteBuf, T> writer) {
		buf.writeVarInt(values.size());
		for (T value : values) {
			writer.accept(buf, value);
		}
	}

	public static <T> List<T> read(FriendlyByteBuf buf, Function<FriendlyByteBuf, T> reader) {
		int size = buf.readVarInt();
		List<T> values = new ArrayList<>(Math.min(size, 65536));
		for (int i = 0; i < size; i++) {
			values.add(reader.apply(buf));
		}
		return values;
	}
}
