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
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Petite fiche du villageois, épinglée à gauche de la fenêtre de commerce (à droite s'il manque de place) :
 * tête, nom, métier, santé, village, lit, poste et famille. Les données sont envoyées par le serveur à l'ouverture
 * des échanges ({@link VillagerCard}).
 */
final class TradeCard {

	/** Taille de la fenêtre de commerce vanilla. */
	private static final int IMAGE_W = 276;
	private static final int IMAGE_H = 166;
	private static final int MIN_W = 90;
	private static final int MAX_W = 136;
	private static final int PAPER = 0xFFF3E5C0;
	private static final int PAPER_EDGE = 0xFFC9AE7C;
	private static final int INK = 0xFF3B2A1A;
	private static final int FADED = 0xFF7A6548;
	private static final int GOLD = 0xFFB8860B;
	private static final int MARRIED = 0xFFC2185B;
	private static final int PIN = 0xFFC0392B;

	private record Line(Component text, int color) {
	}

	private static VillagerCard current;
	private static Screen hooked;

	private TradeCard() {
	}

	static void init() {
		ClientPlayNetworking.registerGlobalReceiver(VillagerCard.TYPE, (payload, context) ->
				context.client().execute(() -> current = payload));
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof MerchantScreen && screen != hooked) {
				hooked = screen;
				ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, delta) -> render(s, g));
				ScreenEvents.remove(screen).register(s -> {
					hooked = null;
					current = null;
				});
			}
		});
	}

	private static void render(Screen screen, GuiGraphicsExtractor g) {
		VillagerCard c = current;
		if (c == null) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		int left = (screen.width - IMAGE_W) / 2;
		int top = (screen.height - IMAGE_H) / 2;
		int w = Math.min(MAX_W, left - 8);
		int x = left - 4 - w;
		if (w < MIN_W) {
			w = Math.min(MAX_W, screen.width - (left + IMAGE_W) - 8);
			x = left + IMAGE_W + 4;
			if (w < MIN_W) {
				return;
			}
		}

		int textW = w - 12;
		List<FormattedCharSequence> rows = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();
		List<Integer> gaps = new ArrayList<>();
		for (Line line : lines(c)) {
			if (line == null) {
				gaps.add(rows.size());
				continue;
			}
			for (FormattedCharSequence part : font.split(line.text(), textW)) {
				rows.add(part);
				colors.add(line.color());
			}
		}
		int h = 34 + rows.size() * 10 + gaps.size() * 4 + 4;
		int y = top;

		g.fill(x + 2, y + 2, x + w + 2, y + h + 2, 0x55000000);
		g.fill(x, y, x + w, y + h, PAPER_EDGE);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, PAPER);
		g.fill(x + w / 2 - 1, y + 2, x + w / 2 + 2, y + 5, PIN);

		g.fill(x + 5, y + 7, x + 23, y + 29, PAPER_EDGE);
		VillagerFace.draw(g, x + 6, y + 8, 2, c.biome(), c.profession(), false);
		int nameX = x + 27;
		int nameW = x + w - 5 - nameX;
		Component name = c.name().isEmpty()
				? Component.translatable("villageboard.gui.unnamed_villager").withStyle(ChatFormatting.ITALIC)
				: Component.literal(c.name()).withStyle(ChatFormatting.BOLD);
		g.text(font, clip(font, name, nameW), nameX, y + 9, INK, false);
		MutableComponent job = Texts.profession(c.profession()).copy();
		boolean employed = !c.profession().endsWith(":none") && !c.profession().endsWith(":nitwit");
		if (employed) {
			job.append(" · ").append(Texts.level(c.level()));
		}
		g.text(font, clip(font, job, nameW), nameX, y + 19, FADED, false);

		int ty = y + 34;
		for (int i = 0; i < rows.size(); i++) {
			if (gaps.contains(i)) {
				g.fill(x + 6, ty + 1, x + w - 6, ty + 2, PAPER_EDGE);
				ty += 4;
			}
			g.text(font, rows.get(i), x + 6, ty, colors.get(i), false);
			ty += 10;
		}
	}

	/** Lignes de la fiche ; null = séparateur. */
	private static List<Line> lines(VillagerCard c) {
		List<Line> lines = new ArrayList<>();
		lines.add(new Line(Component.translatable("villageboard.gui.health", Math.round(c.health()), Math.round(c.maxHealth())), INK));
		lines.add(c.village().isEmpty()
				? new Line(Component.translatable("villageboard.card.no_village"), FADED)
				: new Line(Component.translatable("villageboard.card.village", c.village()), INK));
		if (c.home() == null) {
			lines.add(new Line(Component.translatable("villageboard.card.homeless"), GOLD));
		} else {
			lines.add(new Line(Component.translatable(c.homeBound() ? "villageboard.gui.bound_home" : "villageboard.gui.home",
					coords(c.home())), c.homeBound() ? GOLD : INK));
		}
		boolean employed = !c.profession().endsWith(":none") && !c.profession().endsWith(":nitwit");
		if (employed) {
			lines.add(c.jobSite() == null
					? new Line(Component.translatable("villageboard.gui.job_site.none"), FADED)
					: new Line(Component.translatable(c.jobBound() ? "villageboard.gui.bound" : "villageboard.gui.job_site",
					coords(c.jobSite())), c.jobBound() ? GOLD : INK));
			if (c.locked()) {
				lines.add(new Line(Component.translatable("villageboard.gui.locked"), GOLD));
			}
		}
		lines.add(null);
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
		if (c.born()) {
			lines.add(new Line(Component.translatable("villageboard.gui.born_unknown", c.since()), FADED));
		} else if (c.since() >= 0) {
			lines.add(new Line(Component.translatable("villageboard.gui.since", c.since()), FADED));
		}
		return lines;
	}

	private static String coords(BlockPos pos) {
		return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}

	private static FormattedCharSequence clip(Font font, Component text, int width) {
		if (font.width(text) <= width) {
			return text.getVisualOrderText();
		}
		String s = text.getString();
		while (!s.isEmpty() && font.width(s + "…") > width) {
			s = s.substring(0, s.length() - 1);
		}
		return Component.literal(s + "…").withStyle(text.getStyle()).getVisualOrderText();
	}
}
