package fr.villageboard.net;

import fr.villageboard.village.Kin;
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
		List<VillagerView> villagers,
		List<BedView> beds,
		List<WorkstationView> workstations,
		int bells,
		int golems,
		List<KinView> family) {

	/**
	 * Entrée de l'état civil : habitants, anciens habitants et leur parenté. {@code village} = nom du village où il vit
	 * désormais, ou vide s'il est (ou était) de ce village.
	 */
	public record KinView(UUID uuid, String name, String profession, String type, boolean baby, List<UUID> parents,
			long born, Kin.Fate fate, long fateDay, String village, UUID spouse, long marriedDay,
			List<UUID> divorced, List<UUID> widowed) {

		void write(FriendlyByteBuf buf) {
			buf.writeUUID(uuid);
			buf.writeUtf(name);
			buf.writeUtf(profession);
			buf.writeUtf(type);
			buf.writeBoolean(baby);
			buf.writeCollection(parents, (b, p) -> b.writeUUID(p));
			buf.writeVarLong(born + 1);
			buf.writeEnum(fate);
			buf.writeVarLong(fateDay);
			buf.writeUtf(village);
			buf.writeNullable(spouse, (b, p) -> b.writeUUID(p));
			buf.writeVarLong(marriedDay);
			buf.writeCollection(divorced, (b, p) -> b.writeUUID(p));
			buf.writeCollection(widowed, (b, p) -> b.writeUUID(p));
		}

		static KinView read(FriendlyByteBuf buf) {
			return new KinView(buf.readUUID(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readBoolean(),
					buf.readList(b -> b.readUUID()), buf.readVarLong() - 1, buf.readEnum(Kin.Fate.class), buf.readVarLong(),
					buf.readUtf(), buf.readNullable(b -> b.readUUID()), buf.readVarLong(),
					buf.readList(b -> b.readUUID()), buf.readList(b -> b.readUUID()));
		}
	}

	/** Un lit du territoire (position de la tête du lit). Son occupant se déduit du champ {@code home} des villageois. */
	public record BedView(BlockPos pos, boolean occupied) {
	}

	/** Un poste de travail du territoire et le métier qu'il donne (identifiant complet). */
	public record WorkstationView(BlockPos pos, String profession, boolean occupied) {
	}

	/**
	 * Fiche d'un villageois ; les champs « live » (santé, expérience, échanges, poste de travail) ne sont remplis
	 * que s'il est chargé côté serveur. {@code home} est le dernier lit connu, null = sans abri.
	 */
	public record VillagerView(
			UUID uuid,
			String name,
			String profession,
			String type,
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
			BlockPos boundHome,
			long firstSeenDay,
			boolean born,
			String parents,
			long lastSeen) {

		void write(FriendlyByteBuf buf) {
			buf.writeUUID(uuid);
			buf.writeUtf(name);
			buf.writeUtf(profession);
			buf.writeUtf(type);
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
			buf.writeNullable(boundHome, (b, p) -> b.writeBlockPos(p));
			buf.writeVarLong(firstSeenDay);
			buf.writeBoolean(born);
			buf.writeUtf(parents);
			buf.writeLong(lastSeen);
		}

		static VillagerView read(FriendlyByteBuf buf) {
			return new VillagerView(buf.readUUID(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readBoolean(),
					buf.readBoolean(), buf.readBoolean(), buf.readFloat(), buf.readFloat(), buf.readVarInt(), buf.readVarInt(),
					buf.readBlockPos(), buf.readNullable(b -> b.readBlockPos()), buf.readNullable(b -> b.readBlockPos()), buf.readNullable(b -> b.readBlockPos()),
					buf.readNullable(b -> b.readBlockPos()),
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
		buf.writeCollection(beds, (b, bed) -> {
			b.writeBlockPos(bed.pos());
			b.writeBoolean(bed.occupied());
		});
		buf.writeCollection(workstations, (b, w) -> {
			b.writeBlockPos(w.pos());
			b.writeUtf(w.profession());
			b.writeBoolean(w.occupied());
		});
		buf.writeVarInt(bells);
		buf.writeVarInt(golems);
		buf.writeCollection(family, (b, k) -> k.write(b));
	}

	static BoardView read(FriendlyByteBuf buf) {
		return new BoardView(buf.readUtf(), buf.readUtf(), buf.readVarLong(), buf.readBoolean(), buf.readUtf(),
				buf.readBlockPos(), buf.readVarInt(),
				buf.readList(b -> b.readBlockPos()),
				buf.readList(b -> new NewsEntry(b.readLong(), b.readVarLong(), b.readEnum(NewsType.class), b.readList(FriendlyByteBuf::readUtf))),
				buf.readList(VillagerView::read),
				buf.readList(b -> new BedView(b.readBlockPos(), b.readBoolean())),
				buf.readList(b -> new WorkstationView(b.readBlockPos(), b.readUtf(), b.readBoolean())),
				buf.readVarInt(),
				buf.readVarInt(),
				buf.readList(KinView::read));
	}
}
