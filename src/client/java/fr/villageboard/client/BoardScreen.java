package fr.villageboard.client;

import fr.villageboard.net.BoardView;
import fr.villageboard.net.BoardView.VillagerView;
import fr.villageboard.net.Payloads;
import fr.villageboard.net.Payloads.Action;
import fr.villageboard.village.NewsEntry;
import fr.villageboard.village.Territory;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Le tableau de la mairie : une planche de liège dans un cadre de bois, avec des parchemins épinglés.
 * Trois onglets : la gazette, les habitants (liste par métier puis fiche), le territoire (carte et bornes).
 * La planche s'agrandit avec la fenêtre (entre 320×220 et 460×300 pixels d'interface).
 */
public class BoardScreen extends Screen {

	private static final int WOOD_DARK = 0xFF3B2414;
	private static final int WOOD = 0xFF6B4423;
	private static final int WOOD_LIGHT = 0xFF8A5A30;
	private static final int CORK = 0xFFB5844F;
	private static final int CORK_DARK = 0xFFA06F3F;
	private static final int CORK_LIGHT = 0xFFC4955E;
	private static final int PAPER = 0xFFF3E5C0;
	private static final int PAPER_DIM = 0xFFDCCB9E;
	private static final int PAPER_EDGE = 0xFFC9AE7C;
	private static final int PORTRAIT_BG = 0xFFE8DDB5;
	private static final int INK = 0xFF3B2A1A;
	private static final int FADED = 0xFF7A6548;
	private static final int PIN = 0xFFC0392B;
	private static final int GOLD = 0xFFB8860B;
	private static final int GREEN = 0xFF2E7D32;
	private static final int LINK = 0xFF1F4E8C;
	private static final int RED_LINK = 0xFF8B1A1A;
	private static final int LIGHT_TEXT = 0xFFFFF4D6;
	private static final int ROW_HOVER = 0x22603A1A;
	private static final int ROW_SELECTED = 0x44603A1A;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

	private enum Tab {NEWS, PEOPLE, NEEDS, TERRITORY}

	private record Row(FormattedCharSequence text, int color, int height) {
	}

	private record Category(String key, Component label, Predicate<VillagerView> filter, long count) {
	}

	/** Zone cliquable enregistrée pendant le dessin. */
	private record Hit(int x1, int y1, int x2, int y2, Runnable action) {
		boolean contains(double x, double y) {
			return x >= x1 && x < x2 && y >= y1 && y < y2;
		}
	}

	private BoardView view;
	private Tab tab = Tab.NEWS;
	private String category = "all";
	private UUID selected;
	private int scroll;
	private boolean renaming;
	private boolean confirmReset;
	private EditBox nameBox;
	private String villageNameDraft;
	private List<Row> newsRows = List.of();
	private List<VillageNeeds.Need> needs = List.of();
	private final List<Hit> hits = new ArrayList<>();
	private TerritoryMap map;
	private int left;
	private int top;
	private int boardW;
	private int boardH;

	public BoardScreen(BoardView view) {
		super(Component.translatable("villageboard.gui.title"));
		this.view = view;
	}

	public String villageId() {
		return view.id();
	}

	/** Nouvelles données du serveur (après une action). */
	public void update(BoardView newView) {
		this.view = newView;
		if (selected != null && selectedVillager() == null) {
			selected = null;
		}
		if (map != null) {
			map.setView(newView);
		}
		needs = VillageNeeds.compute(newView);
		renaming = false;
		confirmReset = false;
		rebuildWidgets();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void removed() {
		if (map != null) {
			map.close();
		}
		super.removed();
	}

	// ------------------------------------------------------------------ mise en page

	private int contentLeft() {
		return left + 14;
	}

	private int contentTop() {
		return top + 56;
	}

	private int contentRight() {
		return left + boardW - 14;
	}

	/** Bas de la zone de contenu quand une rangée de boutons occupe le bas de la feuille. */
	private int contentBottomWithButtons() {
		return top + boardH - 38;
	}

	private int buttonRowY() {
		return top + boardH - 32;
	}

	@Override
	protected void init() {
		boardW = Mth.clamp(width - 40, 320, 460);
		boardH = Mth.clamp(height - 50, 220, 300);
		left = (width - boardW) / 2;
		top = (height - boardH) / 2 + 4;
		newsRows = buildNewsRows(contentRight() - contentLeft() - 8);
		needs = VillageNeeds.compute(view);
		nameBox = null;
		if (tab == Tab.PEOPLE && selectedVillager() != null) {
			initSheet(selectedVillager());
		} else if (tab == Tab.TERRITORY) {
			initTerritory();
		}
	}

	private void initSheet(VillagerView v) {
		boolean manage = view.canManage();
		int x = contentLeft();
		int y = buttonRowY();
		int gap = 4;
		int bw = (contentRight() - x - gap * 4) / 5;

		addRenderableWidget(Button.builder(gui("back"), b -> {
			selected = null;
			scroll = 0;
			renaming = false;
			rebuildWidgets();
		}).bounds(x, y, bw, 20).build());

		Button rename = addRenderableWidget(Button.builder(gui("rename"), b -> {
			renaming = !renaming;
			rebuildWidgets();
		}).bounds(x + (bw + gap), y, bw, 20).build());
		rename.active = manage && v.loaded();
		rename.setTooltip(Tooltip.create(gui("rename.tooltip")));

		Button locate = addRenderableWidget(Button.builder(gui("locate"), b -> {
			send(Action.LOCATE, v.uuid(), "");
			onClose();
		}).bounds(x + 2 * (bw + gap), y, bw, 20).build());
		locate.setTooltip(Tooltip.create(gui("locate.tooltip")));

		if (v.loaded()) {
			Button lock = addRenderableWidget(Button.builder(gui(v.locked() ? "unlock" : "lock"),
					b -> send(Action.LOCK, v.uuid(), "")).bounds(x + 3 * (bw + gap), y, bw, 20).build());
			lock.active = manage && v.employed();
			lock.setTooltip(Tooltip.create(gui("lock.tooltip")));

			Button reset = addRenderableWidget(Button.builder(
					confirmReset ? gui("confirm").withStyle(ChatFormatting.RED) : gui("reset"), b -> {
						if (confirmReset) {
							confirmReset = false;
							send(Action.RESET, v.uuid(), "");
						} else {
							confirmReset = true;
							rebuildWidgets();
						}
					}).bounds(x + 4 * (bw + gap), y, contentRight() - (x + 4 * (bw + gap)), 20).build());
			reset.active = manage && v.employed();
			reset.setTooltip(Tooltip.create(gui("reset.tooltip")));
		} else {
			Button forget = addRenderableWidget(Button.builder(gui("forget"),
					b -> send(Action.FORGET, v.uuid(), "")).bounds(x + 3 * (bw + gap), y, contentRight() - (x + 3 * (bw + gap)), 20).build());
			forget.active = manage;
			forget.setTooltip(Tooltip.create(gui("forget.tooltip")));
		}

		if (renaming) {
			int bx = contentRight() - 62;
			nameBox = new EditBox(font, bx - 144, y - 24, 140, 18, gui("rename"));
			nameBox.setMaxLength(32);
			nameBox.setValue(v.name());
			nameBox.setHint(gui("unnamed"));
			addRenderableWidget(nameBox);
			setFocused(nameBox);
			addRenderableWidget(Button.builder(gui("ok"), b -> submitRename()).bounds(bx, y - 25, 62, 20).build());
		}
	}

	private void initTerritory() {
		int x = contentLeft();
		int y = buttonRowY();
		addRenderableWidget(Button.builder(
				Component.translatable("villageboard.gui.show_borders", gui(BorderDisplay.showAlways() ? "yes" : "no")), b -> {
					BorderDisplay.toggleShowAlways();
					rebuildWidgets();
				}).bounds(x, y, 124, 20).build())
				.setTooltip(Tooltip.create(gui("show_borders.tooltip")));
		if (view.canManage()) {
			int bx = contentRight() - 62;
			EditBox villageName = new EditBox(font, x + 130, y + 1, bx - 4 - (x + 130), 18, gui("village_name"));
			villageName.setMaxLength(32);
			villageName.setValue(villageNameDraft != null ? villageNameDraft : view.name());
			villageName.setResponder(s -> villageNameDraft = s);
			addRenderableWidget(villageName);
			addRenderableWidget(Button.builder(gui("rename"), b -> {
				send(Action.RENAME_VILLAGE, Payloads.BoardAction.NONE, villageName.getValue());
				villageNameDraft = null;
			}).bounds(bx, y, 62, 20).build());
		}
		if (map == null) {
			map = new TerritoryMap(minecraft, this::openSheetFromMap);
			map.setView(view);
		}
		map.setBounds(x + 2, contentTop() + 2, mapWidth(), contentBottomWithButtons() - contentTop() - 4);
	}

	private int mapWidth() {
		return contentRight() - contentLeft() - 118;
	}

	private void submitRename() {
		VillagerView v = selectedVillager();
		if (v != null && nameBox != null) {
			send(Action.RENAME, v.uuid(), nameBox.getValue());
		}
		renaming = false;
		rebuildWidgets();
	}

	private void send(Action action, UUID target, String arg) {
		ClientPlayNetworking.send(new Payloads.BoardAction(view.id(), action, target, arg));
	}

	// ------------------------------------------------------------------ rendu

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(g, mouseX, mouseY, partialTick);
		drawBoard(g);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		hits.clear();
		drawHeader(g, mouseX, mouseY);
		switch (tab) {
			case NEWS -> drawNews(g);
			case PEOPLE -> {
				VillagerView v = selectedVillager();
				if (v == null) {
					drawPeople(g, mouseX, mouseY);
				} else {
					drawSheet(g, v, mouseX, mouseY);
				}
			}
			case NEEDS -> drawNeeds(g, mouseX, mouseY);
			case TERRITORY -> drawTerritory(g, mouseX, mouseY);
		}
		super.extractRenderState(g, mouseX, mouseY, partialTick);
	}

	/** Cadre en bois, petit toit et liège moucheté. */
	private void drawBoard(GuiGraphicsExtractor g) {
		int x1 = left;
		int y1 = top;
		int x2 = left + boardW;
		int y2 = top + boardH;
		g.fill(x1 - 14, y1 - 16, x2 + 14, y1 - 10, WOOD_DARK);
		g.fill(x1 - 12, y1 - 10, x2 + 12, y1 - 8, WOOD);
		g.fill(x1 - 8, y1 - 8, x2 + 8, y2 + 8, WOOD_DARK);
		g.fill(x1 - 6, y1 - 6, x2 + 6, y2 + 6, WOOD);
		g.fill(x1 - 6, y1 - 6, x2 + 6, y1 - 5, WOOD_LIGHT);
		g.fill(x1, y1, x2, y2, CORK);
		for (int y = y1 + 1; y < y2 - 1; y += 4) {
			for (int x = x1 + 1 + (y % 3); x < x2 - 1; x += 5) {
				int h = (x * 73856093) ^ (y * 19349663);
				if ((h & 7) == 0) {
					g.fill(x, y, x + 1, y + 1, CORK_DARK);
				} else if ((h & 15) == 3) {
					g.fill(x, y, x + 1, y + 1, CORK_LIGHT);
				}
			}
		}
		paper(g, left + 8, top + 50, left + boardW - 8, top + boardH - 8, PAPER);
	}

	/** Plaque du nom, onglets en forme de notes épinglées, jour et population. */
	private void drawHeader(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int plaqueHalf = Math.max(90, font.width(view.name()) / 2 + 24);
		int cx = left + boardW / 2;
		paper(g, cx - plaqueHalf, top + 4, cx + plaqueHalf, top + 22, PAPER);
		pin(g, cx - plaqueHalf + 4, top + 6);
		pin(g, cx + plaqueHalf - 7, top + 6);
		MutableComponent title = Component.literal(view.name()).withStyle(ChatFormatting.BOLD);
		g.text(font, title, cx - font.width(title) / 2, top + 9, INK, false);
		if (mouseX >= cx - plaqueHalf && mouseX < cx + plaqueHalf && mouseY >= top + 4 && mouseY < top + 22) {
			g.setComponentTooltipForNextFrame(font, List.of(
					Component.translatable("villageboard.gui.founder", view.founderName()),
					Component.translatable("villageboard.gui.population", view.villagers().size())), mouseX, mouseY);
		}
		Component day = Component.translatable("villageboard.gui.day", view.day());
		if (font.width(day) < cx - plaqueHalf - left - 12) {
			g.text(font, day, left + 8, top + 9, LIGHT_TEXT, true);
		}

		Tab[] tabs = Tab.values();
		for (int i = 0; i < tabs.length; i++) {
			Tab t = tabs[i];
			int x1 = left + 8 + i * 72;
			int x2 = x1 + 68;
			boolean active = t == tab;
			int y1 = active ? top + 28 : top + 30;
			Hit hit = new Hit(x1, y1, x2, top + 46, () -> selectTab(t));
			paper(g, x1, y1, x2, top + 46, active ? PAPER : hit.contains(mouseX, mouseY) ? 0xFFE8D8AE : PAPER_DIM);
			long alerts = t == Tab.NEEDS ? VillageNeeds.important(needs) : 0;
			boolean urgent = t == Tab.NEEDS && needs.stream().anyMatch(n -> n.severity() == VillageNeeds.Severity.URGENT);
			pin(g, x1 + 33, y1 + 1);
			if (urgent) {
				g.fill(x1 + 32, y1, x1 + 37, y1 + 5, 0xFFFF3B30);
			}
			MutableComponent label = gui("tab." + t.name().toLowerCase());
			if (alerts > 0) {
				label = label.append(" (" + alerts + ")");
			}
			g.text(font, label, x1 + 34 - font.width(label) / 2, y1 + 6, active ? INK : FADED, false);
			hits.add(hit);
		}

	}

	private void drawNews(GuiGraphicsExtractor g) {
		int x1 = contentLeft() + 2;
		int y1 = contentTop();
		int x2 = contentRight() - 6;
		int y2 = top + boardH - 14;
		if (newsRows.isEmpty()) {
			g.text(font, gui("no_news"), x1, y1, FADED, false);
			return;
		}
		int total = newsRows.stream().mapToInt(Row::height).sum();
		scroll = Mth.clamp(scroll, 0, Math.max(0, total - (y2 - y1)));
		g.enableScissor(x1, y1, x2, y2);
		int y = y1 - scroll;
		for (Row row : newsRows) {
			if (y + row.height() > y1 && y < y2) {
				g.text(font, row.text(), x1, y, row.color(), false);
			}
			y += row.height();
		}
		g.disableScissor();
		scrollbar(g, x2 + 2, y1, y2, total);
	}

	/** Une note épinglée par besoin, du plus grave au moins grave ; un clic emmène là où agir. */
	private void drawNeeds(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int x1 = contentLeft();
		int y1 = contentTop() - 2;
		int x2 = contentRight() - 6;
		int y2 = top + boardH - 12;
		if (needs.isEmpty()) {
			g.text(font, gui("needs.none"), x1 + 2, y1 + 2, GREEN, false);
			return;
		}
		int textX = x1 + 26;
		int textWidth = x2 - textX - 4;
		List<Integer> heights = new ArrayList<>();
		int total = 0;
		for (VillageNeeds.Need need : needs) {
			int lines = font.split(need.detail(), textWidth).size();
			int h = 6 + 11 + lines * 10 + (need.icons().size() > 1 ? 19 : 0) + 4;
			heights.add(h);
			total += h + 5;
		}
		scroll = Mth.clamp(scroll, 0, Math.max(0, total - (y2 - y1)));
		g.enableScissor(x1 - 2, y1, x2 + 2, y2);
		int y = y1 - scroll;
		ItemStack hoveredIcon = null;
		for (int i = 0; i < needs.size(); i++) {
			VillageNeeds.Need need = needs.get(i);
			int h = heights.get(i);
			if (y + h > y1 && y < y2) {
				Hit hit = new Hit(x1, Math.max(y, y1), x2, Math.min(y + h, y2), () -> goTo(need.target()));
				boolean over = hit.contains(mouseX, mouseY) && need.target() != VillageNeeds.Target.NONE;
				g.fill(x1, y, x2, y + h, PAPER_EDGE);
				g.fill(x1 + 1, y + 1, x2 - 1, y + h - 1, over ? 0xFFFBF1D6 : 0xFFF7ECCD);
				g.fill(x1 + 1, y + 1, x1 + 4, y + h - 1, need.severity().color);
				if (!need.icons().isEmpty()) {
					g.item(need.icons().getFirst(), x1 + 7, y + 5);
				}
				g.text(font, need.title().copy().withStyle(ChatFormatting.BOLD), textX, y + 6, INK, false);
				int ty = line(g, need.detail(), textX, y + 18, textWidth, FADED);
				if (need.icons().size() > 1) {
					int ix = textX;
					for (ItemStack icon : need.icons()) {
						g.item(icon, ix, ty + 1);
						if (mouseX >= ix && mouseX < ix + 16 && mouseY >= ty + 1 && mouseY < ty + 17) {
							hoveredIcon = icon;
						}
						ix += 18;
					}
				}
				if (need.target() != VillageNeeds.Target.NONE) {
					hits.add(hit);
					if (over) {
						Component go = gui("needs.go." + need.target().name().toLowerCase());
						g.text(font, go, x2 - 4 - font.width(go), y + 6, LINK, false);
					}
				}
			}
			y += h + 5;
		}
		g.disableScissor();
		scrollbar(g, x2 + 2, y1, y2, total);
		if (hoveredIcon != null) {
			g.setTooltipForNextFrame(font, hoveredIcon.getHoverName(), mouseX, mouseY);
		}
	}

	/** Emmène là où agir pour un besoin : liste des sans-abri, des sans-emploi, ou carte. */
	private void goTo(VillageNeeds.Target target) {
		switch (target) {
			case HOMELESS -> {
				tab = Tab.PEOPLE;
				category = "homeless";
				selected = null;
			}
			case UNEMPLOYED -> {
				tab = Tab.PEOPLE;
				category = "minecraft:none";
				selected = null;
			}
			case MAP -> tab = Tab.TERRITORY;
			default -> {
				return;
			}
		}
		scroll = 0;
		renaming = false;
		confirmReset = false;
		rebuildWidgets();
	}

	private List<Row> buildNewsRows(int width) {
		List<Row> rows = new ArrayList<>();
		for (NewsEntry e : view.news()) {
			Component header = Component.literal(e.type().symbol + " ")
					.append(Component.translatable("villageboard.gui.day", e.day()))
					.append(" · " + TIME.format(Instant.ofEpochMilli(e.time())));
			rows.add(new Row(header.getVisualOrderText(), e.type().color, 10));
			for (FormattedCharSequence line : font.split(Texts.news(e), width)) {
				rows.add(new Row(line, INK, 10));
			}
			rows.add(new Row(FormattedCharSequence.EMPTY, INK, 5));
		}
		return rows;
	}

	private void drawPeople(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int x1 = contentLeft();
		int y1 = contentTop();
		int y2 = top + boardH - 12;

		int cy = y1;
		for (Category c : categories()) {
			boolean active = c.key().equals(category);
			Hit hit = new Hit(x1 - 2, cy - 2, x1 + 106, cy + 12, () -> {
				category = c.key();
				scroll = 0;
			});
			if (active) {
				g.fill(hit.x1, hit.y1, hit.x2, hit.y2, ROW_SELECTED);
			} else if (hit.contains(mouseX, mouseY)) {
				g.fill(hit.x1, hit.y1, hit.x2, hit.y2, ROW_HOVER);
			}
			g.text(font, c.label(), x1, cy + 1, active ? INK : FADED, false);
			String count = String.valueOf(c.count());
			g.text(font, count, x1 + 104 - font.width(count), cy + 1, FADED, false);
			hits.add(hit);
			cy += 14;
		}
		g.fill(x1 + 110, y1, x1 + 111, y2, PAPER_EDGE);

		int lx1 = x1 + 116;
		int lx2 = contentRight() - 6;
		List<VillagerView> list = filtered();
		if (list.isEmpty()) {
			g.text(font, gui("nobody"), lx1, y1, FADED, false);
			return;
		}
		int rowH = 22;
		int total = list.size() * rowH;
		scroll = Mth.clamp(scroll, 0, Math.max(0, total - (y2 - y1)));
		g.enableScissor(lx1 - 2, y1, lx2, y2);
		for (int i = 0; i < list.size(); i++) {
			int ry = y1 + i * rowH - scroll;
			if (ry + rowH < y1 || ry > y2) {
				continue;
			}
			VillagerView v = list.get(i);
			Hit hit = new Hit(lx1 - 2, Math.max(ry, y1), lx2, Math.min(ry + rowH, y2), () -> openSheet(v));
			if (hit.contains(mouseX, mouseY)) {
				g.fill(hit.x1, hit.y1, hit.x2, hit.y2, ROW_HOVER);
			}
			hits.add(hit);
			g.item(Texts.icon(v.baby() ? "child" : v.profession()), lx1, ry + 3);
			g.text(font, Texts.listName(v.name()), lx1 + 20, ry + 2, v.loaded() && !v.name().isEmpty() ? INK : FADED, false);
			g.text(font, describe(v), lx1 + 20, ry + 12, FADED, false);
			Component right = v.loaded() ? distance(v.pos()) : gui("absent");
			g.text(font, right, lx2 - 4 - font.width(right), ry + 2, FADED, false);
			Component badge = v.bound() != null || v.boundHome() != null ? gui("bound_short")
					: v.locked() ? gui("locked_short") : null;
			if (badge != null) {
				g.text(font, badge, lx2 - 4 - font.width(badge), ry + 12, GOLD, false);
			}
		}
		g.disableScissor();
		scrollbar(g, lx2 + 2, y1, y2, total);
	}

	private void drawSheet(GuiGraphicsExtractor g, VillagerView v, int mouseX, int mouseY) {
		int x1 = contentLeft() + 2;
		int y1 = contentTop();

		int px2 = x1 + 76;
		int py2 = y1 + 100;
		g.fill(x1 - 1, y1 - 1, px2 + 1, py2 + 1, PAPER_EDGE);
		g.fill(x1, y1, px2, py2, PORTRAIT_BG);
		LivingEntity entity = findEntity(v.uuid());
		if (entity != null) {
			InventoryScreen.extractEntityInInventoryFollowsMouse(g, x1, y1, px2, py2, 36, 0.0625f, mouseX, mouseY, entity);
		} else {
			g.item(Texts.icon(v.baby() ? "child" : v.profession()), (x1 + px2) / 2 - 8, (y1 + py2) / 2 - 14);
			Component away = gui("out_of_sight");
			g.text(font, away, (x1 + px2) / 2 - font.width(away) / 2, py2 - 14, FADED, false);
		}

		int tx = x1 + 86;
		int width = contentRight() - 6 - tx;
		int ty = y1;
		Component title = v.name().isEmpty()
				? gui("unnamed_villager").withStyle(ChatFormatting.BOLD, ChatFormatting.ITALIC)
				: Component.literal(v.name()).withStyle(ChatFormatting.BOLD);
		g.text(font, title, tx, ty, INK, false);
		ty += 14;
		ty = line(g, describeLong(v), tx, ty, width, INK);
		if (v.locked()) {
			ty = line(g, gui("locked"), tx, ty, width, GOLD);
		}
		if (v.bound() != null) {
			BlockPos station = v.bound();
			Component bound = Component.translatable("villageboard.gui.bound", coords(station));
			g.text(font, bound, tx, ty, GOLD, false);
			int lx = link(g, gui("show_on_map"), tx + font.width(bound) + 4, ty, mouseX, mouseY, () -> showOnMap(station));
			if (view.canManage() && v.loaded()) {
				link(g, gui("unbind"), lx + 4, ty, mouseX, mouseY, () -> send(Action.UNBIND, v.uuid(), ""));
			}
			ty += 10;
		} else if (v.employed()) {
			ty = line(g, gui("not_bound"), tx, ty, width, FADED);
		}
		ty += 3;
		if (v.loaded()) {
			ty = line(g, Component.translatable("villageboard.gui.health", Math.round(v.health()), Math.round(v.maxHealth())), tx, ty, width, INK);
			if (v.employed()) {
				ty = line(g, Component.translatable("villageboard.gui.xp_trades", v.xp(), v.trades()), tx, ty, width, INK);
			}
		}
		ty = line(g, Component.translatable("villageboard.gui.position", coords(v.pos())).append(" · ").append(distance(v.pos())),
				tx, ty, width, INK);
		if (v.loaded()) {
			ty = line(g, v.jobSite() != null
					? Component.translatable("villageboard.gui.job_site", coords(v.jobSite()))
					: gui("job_site.none"), tx, ty, width, INK);
		}
		if (v.boundHome() != null) {
			BlockPos bed = v.boundHome();
			Component home = Component.translatable("villageboard.gui.bound_home", coords(bed));
			g.text(font, home, tx, ty, GOLD, false);
			int lx = link(g, gui("show_on_map"), tx + font.width(home) + 4, ty, mouseX, mouseY, () -> showOnMap(bed));
			if (view.canManage() && v.loaded()) {
				link(g, gui("unbind"), lx + 4, ty, mouseX, mouseY, () -> send(Action.UNBIND_HOME, v.uuid(), ""));
			}
			ty += 10;
		} else if (v.home() != null) {
			BlockPos bed = v.home();
			Component home = Component.translatable("villageboard.gui.home", coords(bed));
			g.text(font, home, tx, ty, INK, false);
			link(g, gui("show_on_map"), tx + font.width(home) + 4, ty, mouseX, mouseY, () -> showOnMap(bed));
			ty += 10;
		} else {
			ty = line(g, freeBeds() > 0 ? gui("homeless.free_beds") : gui("homeless.no_bed"), tx, ty, width, GOLD);
		}
		ty += 3;
		if (v.born()) {
			ty = line(g, v.parents().isEmpty()
					? Component.translatable("villageboard.gui.born_unknown", v.firstSeenDay())
					: Component.translatable("villageboard.gui.born", v.firstSeenDay(), v.parents()), tx, ty, width, GREEN);
		} else {
			ty = line(g, Component.translatable("villageboard.gui.since", v.firstSeenDay()), tx, ty, width, FADED);
		}
		if (!v.loaded()) {
			line(g, Component.translatable("villageboard.gui.last_seen", Texts.ago(v.lastSeen())), tx, ty, width, FADED);
		}
		if (renaming) {
			g.text(font, gui("new_name"), contentLeft() + 2, buttonRowY() - 19, INK, false);
		}
	}

	private void drawTerritory(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		if (map != null) {
			map.render(g, font, mouseX, mouseY);
		}
		int x1 = contentRight() - 108;
		int width = 106;
		int n = view.polygon().size();
		int ty = contentTop();
		g.text(font, gui("territory").withStyle(ChatFormatting.BOLD), x1, ty, INK, false);
		ty += 12;
		ty = line(g, Component.translatable("villageboard.gui.bornes", n), x1, ty, width, INK);
		ty = line(g, Component.translatable("villageboard.gui.area", Territory.area(view.polygon(), view.defaultRadius())), x1, ty, width, INK);
		if (n < 3) {
			ty = line(g, Component.translatable("villageboard.gui.mode_circle", view.defaultRadius(), 3 - n), x1, ty, width, GOLD);
		}
		ty += 6;

		int beds = view.beds().size();
		int free = freeBeds();
		long homeless = view.villagers().stream().filter(v -> v.home() == null).count();
		g.text(font, gui("housing").withStyle(ChatFormatting.BOLD), x1, ty, INK, false);
		ty += 12;
		ty = line(g, Component.translatable("villageboard.gui.beds", beds, free), x1, ty, width, INK);
		ty = line(g, Component.translatable("villageboard.gui.homeless_count", homeless), x1, ty, width, homeless > 0 ? GOLD : INK);
		if (free == 0) {
			line(g, gui("no_free_bed"), x1, ty + 2, width, RED_LINK);
		}
	}

	private int freeBeds() {
		return (int) view.beds().stream().filter(b -> !b.occupied()).count();
	}

	/** Lien « voir sur la carte » : ouvre l'onglet Territoire centré sur ce point. */
	private void showOnMap(BlockPos pos) {
		tab = Tab.TERRITORY;
		scroll = 0;
		renaming = false;
		confirmReset = false;
		rebuildWidgets();
		if (map != null) {
			map.focus(pos);
		}
	}

	// ------------------------------------------------------------------ petits éléments graphiques

	private static void paper(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
		g.fill(x1 + 2, y1 + 2, x2 + 2, y2 + 2, 0x55000000);
		g.fill(x1, y1, x2, y2, PAPER_EDGE);
		g.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, color);
	}

	private static void pin(GuiGraphicsExtractor g, int x, int y) {
		g.fill(x, y, x + 3, y + 3, PIN);
		g.fill(x, y, x + 1, y + 1, 0xFFE57373);
	}

	private void scrollbar(GuiGraphicsExtractor g, int x, int y1, int y2, int total) {
		int visible = y2 - y1;
		if (total <= visible) {
			return;
		}
		g.fill(x, y1, x + 2, y2, PAPER_EDGE);
		int thumb = Math.max(12, visible * visible / total);
		int pos = y1 + (int) ((long) (visible - thumb) * scroll / (total - visible));
		g.fill(x, pos, x + 2, pos + thumb, FADED);
	}

	/** Lien cliquable « [texte] » ; renvoie l'abscisse de sa fin. */
	private int link(GuiGraphicsExtractor g, Component text, int x, int y, int mouseX, int mouseY, Runnable action) {
		Hit hit = new Hit(x, y - 1, x + font.width(text), y + 9, action);
		g.text(font, text, x, y, hit.contains(mouseX, mouseY) ? RED_LINK : LINK, false);
		hits.add(hit);
		return hit.x2();
	}

	/** Texte avec retour à la ligne ; renvoie la position y suivante. */
	private int line(GuiGraphicsExtractor g, Component text, int x, int y, int width, int color) {
		for (FormattedCharSequence part : font.split(text, width)) {
			g.text(font, part, x, y, color, false);
			y += 10;
		}
		return y;
	}

	// ------------------------------------------------------------------ données

	private void selectTab(Tab t) {
		if (t != tab) {
			tab = t;
			scroll = 0;
			renaming = false;
			confirmReset = false;
			rebuildWidgets();
		}
	}

	private void openSheet(VillagerView v) {
		selected = v.uuid();
		renaming = false;
		confirmReset = false;
		rebuildWidgets();
	}

	private void openSheetFromMap(VillagerView v) {
		tab = Tab.PEOPLE;
		scroll = 0;
		openSheet(v);
	}

	private VillagerView selectedVillager() {
		if (selected == null) {
			return null;
		}
		return view.villagers().stream().filter(v -> v.uuid().equals(selected)).findFirst().orElse(null);
	}

	private List<Category> categories() {
		List<Category> result = new ArrayList<>();
		result.add(category("all", gui("cat.all"), v -> true));
		result.add(category("child", gui("cat.children"), VillagerView::baby));
		result.add(category("homeless", gui("cat.homeless"), v -> v.home() == null));
		List<String> professions = view.villagers().stream()
				.filter(v -> !v.baby())
				.map(VillagerView::profession)
				.distinct()
				.sorted(Comparator
						.comparing((String p) -> p.endsWith(":none") ? 0 : p.endsWith(":nitwit") ? 2 : 1)
						.thenComparing(p -> Texts.profession(p).getString()))
				.toList();
		for (String p : professions) {
			result.add(category(p, Texts.profession(p), v -> !v.baby() && v.profession().equals(p)));
		}
		return result.stream().filter(c -> c.key().equals("all") || c.count() > 0).toList();
	}

	private Category category(String key, Component label, Predicate<VillagerView> filter) {
		return new Category(key, label, filter, view.villagers().stream().filter(filter).count());
	}

	/** Villageois nommés d'abord (ordre alphabétique), puis les anonymes par métier et distance. */
	private List<VillagerView> filtered() {
		Predicate<VillagerView> filter = categories().stream()
				.filter(c -> c.key().equals(category))
				.findFirst()
				.map(Category::filter)
				.orElse(v -> true);
		return view.villagers().stream()
				.filter(filter)
				.sorted(Comparator
						.comparing((VillagerView v) -> v.name().isEmpty())
						.thenComparing(VillagerView::name, String.CASE_INSENSITIVE_ORDER)
						.thenComparing(v -> v.baby() ? "" : Texts.profession(v.profession()).getString())
						.thenComparingDouble(v -> distanceSq(v.pos())))
				.toList();
	}

	private Component describe(VillagerView v) {
		if (v.baby()) {
			return gui("child");
		}
		MutableComponent c = Texts.profession(v.profession()).copy();
		return v.employed() ? c.append(" · ").append(Texts.level(v.level())) : c;
	}

	private Component describeLong(VillagerView v) {
		if (v.baby()) {
			return gui("child");
		}
		MutableComponent c = Component.translatable("villageboard.gui.profession", Texts.profession(v.profession()));
		return v.employed()
				? c.append(Component.translatable("villageboard.gui.level", Texts.level(v.level()), v.level()))
				: c;
	}

	private double distanceSq(BlockPos pos) {
		return minecraft.player == null ? 0 : minecraft.player.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
	}

	private Component distance(BlockPos pos) {
		if (minecraft.player == null) {
			return Component.empty();
		}
		return Component.translatable("villageboard.gui.distance", (int) Math.sqrt(distanceSq(pos)));
	}

	private static String coords(BlockPos pos) {
		return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}

	private LivingEntity findEntity(UUID uuid) {
		if (minecraft.level == null) {
			return null;
		}
		for (Entity e : minecraft.level.entitiesForRendering()) {
			if (e instanceof LivingEntity living && e.getUUID().equals(uuid)) {
				return living;
			}
		}
		return null;
	}

	private static MutableComponent gui(String key) {
		return Component.translatable("villageboard.gui." + key);
	}

	// ------------------------------------------------------------------ entrées

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		for (Hit hit : List.copyOf(hits)) {
			if (hit.contains(event.x(), event.y())) {
				hit.action().run();
				return true;
			}
		}
		return tab == Tab.TERRITORY && map != null && map.mouseClicked(event.x(), event.y(), event.button());
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (tab == Tab.TERRITORY && map != null && map.mouseDragged(event.x(), event.y())) {
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (tab == Tab.TERRITORY && map != null) {
			map.mouseReleased(event.x(), event.y());
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (tab == Tab.TERRITORY) {
			return map != null && map.mouseScrolled(mouseX, mouseY, scrollY);
		}
		scroll = Math.max(0, scroll - (int) (scrollY * 12));
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (renaming && nameBox != null && nameBox.isFocused()
				&& (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
			submitRename();
			return true;
		}
		return super.keyPressed(event);
	}
}
