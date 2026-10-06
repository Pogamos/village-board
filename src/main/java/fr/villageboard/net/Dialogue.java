package fr.villageboard.net;

import fr.villageboard.VillageBoard;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/**
 * Serveur → client : réplique d'un villageois à qui le joueur parle (boîte de dialogue en bas de l'écran).
 * {@code line} garde ses variables ({joueur}, {nom}, {metier}, {village}, {conjoint}) : le client les remplace,
 * ce qui lui permet de traduire le métier.
 *
 * @param canTrade le villageois a des échanges : la boîte rappelle qu'un second clic droit les ouvre
 */
public record Dialogue(UUID villager, String name, String profession, String biome, boolean baby, String line,
		String village, String spouse, String player, boolean canTrade) implements CustomPacketPayload {

	public static final Type<Dialogue> TYPE = new Type<>(VillageBoard.id("dialogue"));
	public static final StreamCodec<RegistryFriendlyByteBuf, Dialogue> CODEC = StreamCodec.of(
			(buf, d) -> d.write(buf), Dialogue::read);

	private void write(FriendlyByteBuf buf) {
		buf.writeUUID(villager);
		buf.writeUtf(name);
		buf.writeUtf(profession);
		buf.writeUtf(biome);
		buf.writeBoolean(baby);
		buf.writeUtf(line, 1024);
		buf.writeUtf(village);
		buf.writeUtf(spouse);
		buf.writeUtf(player);
		buf.writeBoolean(canTrade);
	}

	private static Dialogue read(FriendlyByteBuf buf) {
		return new Dialogue(buf.readUUID(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readBoolean(),
				buf.readUtf(1024), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readBoolean());
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
