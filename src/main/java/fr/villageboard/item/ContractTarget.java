package fr.villageboard.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import io.netty.buffer.ByteBuf;

import java.util.UUID;

/** Le villageois inscrit sur un contrat de travail (nom vide = sans nom). */
public record ContractTarget(UUID villager, String name, String profession) {

	public static final Codec<ContractTarget> CODEC = RecordCodecBuilder.create(i -> i.group(
			UUIDUtil.CODEC.fieldOf("villager").forGetter(ContractTarget::villager),
			Codec.STRING.fieldOf("name").forGetter(ContractTarget::name),
			Codec.STRING.fieldOf("profession").forGetter(ContractTarget::profession)
	).apply(i, ContractTarget::new));

	public static final StreamCodec<ByteBuf, ContractTarget> STREAM_CODEC = StreamCodec.composite(
			UUIDUtil.STREAM_CODEC, ContractTarget::villager,
			ByteBufCodecs.STRING_UTF8, ContractTarget::name,
			ByteBufCodecs.STRING_UTF8, ContractTarget::profession,
			ContractTarget::new);
}
