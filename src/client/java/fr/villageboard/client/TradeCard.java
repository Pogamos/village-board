package fr.villageboard.client;

import fr.villageboard.net.VillagerCard;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Petite fiche du villageois épinglée à gauche de la fenêtre de commerce : tête, nom, métier, logement et famille.
 * Les données arrivent du serveur juste après l'ouverture des échanges ({@link VillagerCard}) ; la fenêtre de commerce
 * est alors décalée vers la droite pour faire de la place (voir {@link #shift} et MerchantScreenMixin).
 */
public final class TradeCard {

	/** Taille de la fenêtre de commerce vanilla. */
	private static final int IMAGE_W = 276;
	private static final int IMAGE_H = 166;
	private static final int CARD_W = 130;
	private static final int MIN_W = 80;
	private static final int GAP = 6;
	private static final int MARGIN = 4;
	private static final int PAPER = 0xFFF3E5C0;
	private static final int PAPER_EDGE = 0xFFC9AE7C;
	private static final int INK = 0xFF3B2A1A;
	private static final int FADED = 0xFF7A6548;
	private static final int GOLD = 0xFFB8860B;
	private static final int GREEN = 0xFF2E7D32;
	private static final int MARRIED = 0xFFC2185B;
	private static final int PIN = 0xFFC0392B;

	private record Line(Component text, int color) {
	}

	private static VillagerCard current;

	private TradeCard() {
	}

	static void init() {
		ClientPlayNetworking.registerGlobalReceiver(VillagerCard.TYPE, (payload, context) -> context.client().execute(() -> {
			current = payload;
			// La fenêtre est déjà ouverte : on la remet en page pour la décaler.
			if (context.client().gui.screen() instanceof MerchantScreen screen) {
				screen.resize(screen.width, screen.height);
			}
		}));
		// Fabric recrée les événements d'un écran à chaque init et à chaque remise en page : on s'y réinscrit à chaque fois.
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof MerchantScreen) {
				ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, delta) -> render(s, g));
				ScreenEvents.remove(screen).register(s -> current = null);
			}
		});
	}

	/**
	 * Décalage vers la droite de la fenêtre de commerce : fiche + commerce centrés ensemble, sans que le commerce
	 * dépasse le bord droit de l'écran. 0 sans fiche (marchand ambulant, serveur sans le mod).
	 */
	public static int shift(int screenWidth) {
		if (current == null) {
			return 0;
		}
		int centered = (screenWidth - IMAGE_W) / 2;
		int together = (screenWidth - (IMAGE_W + GAP + CARD_W)) / 2 + CARD_W + GAP;
		int rightmost = screenWidth - IMAGE_W - MARGIN;
		return Math.max(0, Math.min(together, rightmost) - centered);
	}

	private static void render(Screen screen, GuiGraphicsExtractor g) {
		VillagerCard c = current;
		if (c == null) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		int merchantLeft = (screen.width - IMAGE_W) / 2 + shift(screen.width);
		int w = Math.min(CARD_W, merchantLeft - GAP - MARGIN);
		if (w < MIN_W) {
			return;
		}
		int x = merchantLeft - GAP - w;
		int y = (screen.height - IMAGE_H) / 2;

		// En-tête : tête à gauche, nom et métier à droite (sur plusieurs lignes si besoin).
		int headerX = x + 27;
		int headerW = x + w - 5 - headerX;
		Component name = c.name().isEmpty()
				? Component.translatable("villageboard.gui.unnamed_villager").withStyle(ChatFormatting.ITALIC)
				: Component.literal(c.name()).withStyle(ChatFormatting.BOLD);
		List<FormattedCharSequence> nameRows = font.split(name, headerW);
		List<FormattedCharSequence> jobRows = font.split(Texts.profession(c.profession()), headerW);
		int headerH = Math.max(24, 2 + (nameRows.size() + jobRows.size()) * 10);

		int textW = w - 12;
		List<FormattedCharSequence> rows = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();
		for (Line line : lines(c)) {
			for (FormattedCharSequence part : font.split(line.text(), textW)) {
				rows.add(part);
				colors.add(line.color());
			}
		}
		int h = 7 + headerH + 5 + rows.size() * 10 + 4;

		g.fill(x + 2, y + 2, x + w + 2, y + h + 2, 0x55000000);
		g.fill(x, y, x + w, y + h, PAPER_EDGE);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, PAPER);
		g.fill(x + w / 2 - 1, y + 2, x + w / 2 + 2, y + 5, PIN);

		g.fill(x + 5, y + 7, x + 23, y + 29, PAPER_EDGE);
		VillagerFace.draw(g, x + 6, y + 8, 2, c.biome(), c.profession(), false);
		int ty = y + 8;
		for (FormattedCharSequence row : nameRows) {
			g.text(font, row, headerX, ty, INK, false);
			ty += 10;
		}
		for (FormattedCharSequence row : jobRows) {
			g.text(font, row, headerX, ty, FADED, false);
			ty += 10;
		}

		ty = y + 7 + headerH + 2;
		g.fill(x + 6, ty, x + w - 6, ty + 1, PAPER_EDGE);
		ty += 3;
		for (int i = 0; i < rows.size(); i++) {
			g.text(font, rows.get(i), x + 6, ty, colors.get(i), false);
			ty += 10;
		}
	}

	/** Logement, puis famille. */
	private static List<Line> lines(VillagerCard c) {
		List<Line> lines = new ArrayList<>();
		lines.add(c.homeless()
				? new Line(Component.translatable("villageboard.card.homeless"), GOLD)
				: new Line(Component.translatable("villageboard.card.housed"), GREEN));
		switch (c.couple()) {
			case 1 -> lines.add(new Line(Component.translatable("villageboard.gui.family.spouse", Texts.listName(c.partner())), MARRIED));
			case 2 -> lines.add(new Line(Component.translatable("villageboard.gui.family.widowed", Texts.listName(c.partner())), FADED));
			default -> lines.add(new Line(Component.translatable("villageboard.gui.single"), FADED));
		}
		if (!c.parents().isEmpty()) {
			String parents = c.parents().stream().map(p -> Texts.listName(p).getString()).collect(Collectors.joining(" & "));
			lines.add(new Line(Component.translatable("villageboard.gui.family.parents_of", parents), INK));
		}
		if (c.children() > 0 || c.siblings() > 0) {
			lines.add(new Line(Component.translatable("villageboard.card.kin", c.children(), c.siblings()), INK));
		}
		return lines;
	}
}
