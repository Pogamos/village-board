package fr.villageboard.net;

import fr.villageboard.village.NewsEntry;
import fr.villageboard.village.NewsType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

import java.util.List;
import java.util.UUID;

/** Tout ce que le client affiche sur le tableau d'un village. */
public record BoardView(
		String id,
		String name,
		long day,
		boolean canManage,
		String founderName,
		BlockPos board,
		int defaultRadius,
		List<BlockPos> polygon,
		List<NewsEntry> news,
		List<VillagerView> villagers) {

	/** Fiche d'un villageois ; les champs « live » ne sont remplis que s'il est chargé côté serveur. */
	public record VillagerView(
			UUID uuid,
			String name,
			String profession,
			int level,
			boolean baby,
			boolean locked,
			boolean loaded,
			float health,
			float maxHealth,
			int xp,
			int trades,
			BlockPos pos,
			BlockPos jobSite,
			BlockPos home,
			BlockPos bound,
			long firstSeenDay,
			boolean born,
			String parents,
			long lastSeen) {

		void write(FriendlyByteBuf buf) {
			buf.writeUUID(uuid);
			buf.writeUtf(name);
			buf.writeUtf(profession);
			buf.writeVarInt(level);
			buf.writeBoolean(baby);
			buf.writeBoolean(locked);
			buf.writeBoolean(loaded);
			buf.writeFloat(health);
			buf.writeFloat(maxHealth);
			buf.writeVarInt(xp);
			buf.writeVarInt(trades);
			buf.writeBlockPos(pos);
			buf.writeNullable(jobSite, (b, p) -> b.writeBlockPos(p));
			buf.writeNullable(home, (b, p) -> b.writeBlockPos(p));
			buf.writeNullable(bound, (b, p) -> b.writeBlockPos(p));
			buf.writeVarLong(firstSeenDay);
			buf.writeBoolean(born);
			buf.writeUtf(parents);
			buf.writeLong(lastSeen);
		}

		static VillagerView read(FriendlyByteBuf buf) {
			return new VillagerView(buf.readUUID(), buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readBoolean(),
					buf.readBoolean(), buf.readBoolean(), buf.readFloat(), buf.readFloat(), buf.readVarInt(), buf.readVarInt(),
					buf.readBlockPos(), buf.readNullable(b -> b.readBlockPos()), buf.readNullable(b -> b.readBlockPos()), buf.readNullable(b -> b.readBlockPos()),
					buf.readVarLong(), buf.readBoolean(), buf.readUtf(), buf.readLong());
		}

		public boolean employed() {
			return !baby && !profession.equals("minecraft:none") && !profession.equals("minecraft:nitwit");
		}
	}

	void write(FriendlyByteBuf buf) {
		buf.writeUtf(id);
		buf.writeUtf(name);
		buf.writeVarLong(day);
		buf.writeBoolean(canManage);
		buf.writeUtf(founderName);
		buf.writeBlockPos(board);
		buf.writeVarInt(defaultRadius);
		buf.writeCollection(polygon, (b, p) -> b.writeBlockPos(p));
		buf.writeCollection(news, (b, n) -> {
			b.writeLong(n.time());
			b.writeVarLong(n.day());
			b.writeEnum(n.type());
			b.writeCollection(n.args(), FriendlyByteBuf::writeUtf);
		});
		buf.writeCollection(villagers, (b, v) -> v.write(b));
	}

	static BoardView read(FriendlyByteBuf buf) {
		return new BoardView(buf.readUtf(), buf.readUtf(), buf.readVarLong(), buf.readBoolean(), buf.readUtf(),
				buf.readBlockPos(), buf.readVarInt(),
				buf.readList(b -> b.readBlockPos()),
				buf.readList(b -> new NewsEntry(b.readLong(), b.readVarLong(), b.readEnum(NewsType.class), b.readList(FriendlyByteBuf::readUtf))),
				buf.readList(VillagerView::read));
	}
}
