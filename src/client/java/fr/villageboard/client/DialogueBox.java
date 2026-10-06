package fr.villageboard.client;

import fr.villageboard.VillageBoard;
import fr.villageboard.net.Dialogue;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.Locale;

/**
 * Boîte de dialogue en bas de l'écran quand on parle à un villageois : sa tête, son nom, son métier et sa réplique,
 * qui s'écrit lettre par lettre. Elle disparaît après le temps de lecture, si le joueur s'éloigne, ou quand les échanges
 * s'ouvrent (second clic droit).
 */
final class DialogueBox {

	private static final int CHARS_PER_SECOND = 45;
	private static final double MAX_DISTANCE_SQ = 10 * 10;
	private static final int PAPER = 0xF2F3E5C0;
	private static final int PAPER_EDGE = 0xFFC9AE7C;
	private static final int INK = 0xFF3B2A1A;
	private static final int FADED = 0xFF7A6548;
	private static final int PIN = 0xFFC0392B;

	private static Dialogue current;
	private static String text = "";
	private static long shownAt;
	private static Entity speaker;

	private DialogueBox() {
	}

	static void init() {
		ClientPlayNetworking.registerGlobalReceiver(Dialogue.TYPE, (payload, context) ->
				context.client().execute(() -> show(payload)));
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, VillageBoard.id("dialogue"), DialogueBox::render);
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof MerchantScreen) {
				current = null;
			}
		});
	}

	private static void show(Dialogue d) {
		current = d;
		text = resolve(d);
		shownAt = System.currentTimeMillis();
		speaker = null;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) {
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e.getUUID().equals(d.villager())) {
					speaker = e;
					break;
				}
			}
		}
	}

	/** Remplace les variables du fichier des répliques (noms français, ou anglais). */
	private static String resolve(Dialogue d) {
		String job = Texts.profession(d.profession()).getString().toLowerCase(Locale.ROOT);
		return d.line()
				.replace("{joueur}", d.player()).replace("{player}", d.player())
				.replace("{nom}", d.name()).replace("{name}", d.name())
				.replace("{metier}", job).replace("{job}", job)
				.replace("{village}", d.village())
				.replace("{conjoint}", d.spouse()).replace("{spouse}", d.spouse());
	}

	private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		Dialogue d = current;
		Minecraft mc = Minecraft.getInstance();
		if (d == null || mc.gui.screen() != null || mc.player == null) {
			return;
		}
		long elapsed = System.currentTimeMillis() - shownAt;
		long typing = text.length() * 1000L / CHARS_PER_SECOND;
		if (elapsed > typing + 3500 + text.length() * 40L
				|| speaker != null && (!speaker.isAlive() || speaker.distanceToSqr(mc.player) > MAX_DISTANCE_SQ)) {
			current = null;
			return;
		}

		Font font = mc.font;
		int screenW = g.guiWidth();
		int screenH = g.guiHeight();
		int w = Math.min(320, screenW - 40);
		int x = (screenW - w) / 2;
		int textX = x + 30;
		int textW = x + w - 8 - textX;
		Component quote = Component.literal("« " + text + " »");
		List<FormattedCharSequence> full = font.split(quote, textW);
		boolean done = elapsed >= typing;
		int h = Math.max(34, 18 + full.size() * 10 + (d.canTrade() ? 12 : 4));
		int y = screenH - 52 - h;

		g.fill(x + 2, y + 2, x + w + 2, y + h + 2, 0x55000000);
		g.fill(x, y, x + w, y + h, PAPER_EDGE);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, PAPER);
		g.fill(x + w / 2 - 1, y + 2, x + w / 2 + 2, y + 5, PIN);

		g.fill(x + 6, y + 6, x + 24, y + 28, PAPER_EDGE);
		float scale = d.baby() ? 1.4f : 2f;
		VillagerFace.draw(g, x + 7 + (16 - 8 * scale) / 2, y + 7 + (20 - 10 * scale), scale, d.biome(), d.profession(), false);

		MutableComponent name = d.name().isEmpty()
				? Component.translatable("villageboard.gui.unnamed_villager").withStyle(ChatFormatting.ITALIC)
				: Component.literal(d.name()).withStyle(ChatFormatting.BOLD);
		g.text(font, name, textX, y + 6, INK, false);
		Component job = d.baby() ? Component.translatable("villageboard.gui.child") : Texts.profession(d.profession());
		g.text(font, Component.literal(" — ").append(job), textX + font.width(name), y + 6, FADED, false);

		int visible = done ? text.length() : (int) (elapsed * CHARS_PER_SECOND / 1000);
		Component typed = Component.literal("« " + text.substring(0, Math.min(visible, text.length())) + (done ? " »" : ""));
		int ty = y + 18;
		for (FormattedCharSequence line : font.split(typed, textW)) {
			g.text(font, line, textX, ty, INK, false);
			ty += 10;
		}
		if (done && d.canTrade()) {
			Component hint = Component.translatable("villageboard.dialogue.trade");
			boolean blink = (elapsed / 500) % 2 == 0;
			g.text(font, hint, x + w - 8 - font.width(hint), y + h - 11, blink ? FADED : 0xFFA89870, false);
		}
	}
}
