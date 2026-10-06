package fr.villageboard.net;

import fr.villageboard.VillageBoard;
import fr.villageboard.village.VillageManager;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;
import java.util.UUID;

public final class Payloads {

	/** Serveur → client : ouvre (ou met à jour) l'écran du tableau. */
	public record OpenBoard(BoardView view) implements CustomPacketPayload {
		public static final Type<OpenBoard> TYPE = new Type<>(VillageBoard.id("open_board"));
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenBoard> CODEC =
				StreamCodec.of((buf, p) -> p.view.write(buf), buf -> new OpenBoard(BoardView.read(buf)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Serveur → client : limites de tous les villages. */
	public record Borders(List<BorderView> borders) implements CustomPacketPayload {
		public static final Type<Borders> TYPE = new Type<>(VillageBoard.id("borders"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Borders> CODEC = StreamCodec.of(
				(buf, p) -> buf.writeCollection(p.borders, (b, v) -> v.write(b)),
				buf -> new Borders(buf.readList(BorderView::read)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public enum Action {
		REFRESH, RENAME, LOCATE, LOCK, RESET, UNBIND, UNBIND_HOME, FORGET, RENAME_VILLAGE, DIVORCE
	}

	/** Client → serveur : une action depuis le tableau. {@code target} = villageois concerné (ou UUID nul). */
	public record BoardAction(String villageId, Action action, UUID target, String arg) implements CustomPacketPayload {
		public static final Type<BoardAction> TYPE = new Type<>(VillageBoard.id("board_action"));
		public static final StreamCodec<RegistryFriendlyByteBuf, BoardAction> CODEC = StreamCodec.of(
				(buf, p) -> {
					buf.writeUtf(p.villageId);
					buf.writeEnum(p.action);
					buf.writeUUID(p.target);
					buf.writeUtf(p.arg, 64);
				},
				buf -> new BoardAction(buf.readUtf(), buf.readEnum(Action.class), buf.readUUID(), buf.readUtf(64)));

		public static final UUID NONE = new UUID(0, 0);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	private Payloads() {
	}

	public static void init() {
		PayloadTypeRegistry.clientboundPlay().register(OpenBoard.TYPE, OpenBoard.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Borders.TYPE, Borders.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(BoardAction.TYPE, BoardAction.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(BoardAction.TYPE, (payload, context) ->
				context.server().execute(() -> {
					if (VillageManager.get() != null) {
						VillageManager.get().handleAction(context.player(), payload);
					}
				}));
	}
}
