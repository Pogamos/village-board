package fr.villageboard.net;

import fr.villageboard.VillageBoard;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;
import java.util.UUID;

/**
 * Serveur → client, à l'ouverture des échanges avec un villageois : petite fiche affichée à côté de la fenêtre de commerce
 * (nom, métier, logement, famille). {@code name} vide = villageois sans nom.
 *
 * @param couple  0 = célibataire, 1 = marié à {@code partner}, 2 = veuf de {@code partner}
 * @param parents noms des parents connus (vide = sans nom)
 */
public record VillagerCard(UUID uuid, String name, String profession, String biome, boolean homeless,
		int couple, String partner, List<String> parents, int children, int siblings) implements CustomPacketPayload {

	public static final Type<VillagerCard> TYPE = new Type<>(VillageBoard.id("villager_card"));
	public static final StreamCodec<RegistryFriendlyByteBuf, VillagerCard> CODEC = StreamCodec.of(
			(buf, c) -> c.write(buf), VillagerCard::read);

	private void write(FriendlyByteBuf buf) {
		buf.writeUUID(uuid);
		buf.writeUtf(name);
		buf.writeUtf(profession);
		buf.writeUtf(biome);
		buf.writeBoolean(homeless);
		buf.writeVarInt(couple);
		buf.writeUtf(partner);
		buf.writeCollection(parents, FriendlyByteBuf::writeUtf);
		buf.writeVarInt(children);
		buf.writeVarInt(siblings);
	}

	private static VillagerCard read(FriendlyByteBuf buf) {
		return new VillagerCard(buf.readUUID(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readBoolean(),
				buf.readVarInt(), buf.readUtf(), buf.readList(FriendlyByteBuf::readUtf), buf.readVarInt(), buf.readVarInt());
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
