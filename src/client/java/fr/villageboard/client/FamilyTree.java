package fr.villageboard.client;

import fr.villageboard.net.BoardView;
import fr.villageboard.net.BoardView.KinView;
import fr.villageboard.village.Kin;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Arbre généalogique d'un villageois, d'après l'état civil reçu du serveur : grands-parents, parents, le villageois
 * entouré de ses frères et sœurs, puis ses enfants. Cliquer sur un parent recentre l'arbre sur lui.
 */
final class FamilyTree {

	private static final int NODE_H = 22;
	private static final int GAP = 6;
	private static final int INK = 0xFF3B2A1A;
	private static final int FADED = 0xFF7A6548;
	private static final int GOLD = 0xFFB8860B;
	private static final int EDGE = 0xFFC9AE7C;
	private static final int HOVER = 0xFF8B1A1A;
	private static final int LINE = 0xFF8A6D4A;

	/** Case de l'arbre ; {@code uuid} nul pour la case « +n » qui résume les membres qui ne tiennent pas. */
	private record Node(int x, int y, int w, UUID uuid, List<KinView> hidden) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + NODE_H;
		}

		int cx() {
			return x + w / 2;
		}
	}

	private Map<UUID, KinView> kin = Map.of();
	private Set<UUID> residents = Set.of();
	private UUID focus;
	private final List<Node> nodes = new ArrayList<>();

	void setView(BoardView view) {
		Map<UUID, KinView> map = new HashMap<>();
		view.family().forEach(k -> map.put(k.uuid(), k));
		kin = map;
		residents = view.villagers().stream().map(BoardView.VillagerView::uuid).collect(Collectors.toSet());
	}

	UUID focus() {
		return focus;
	}

	void setFocus(UUID uuid) {
		focus = uuid;
	}

	boolean known(UUID uuid) {
		return kin.containsKey(uuid);
	}

	boolean resident(UUID uuid) {
		return residents.contains(uuid);
	}

	// ------------------------------------------------------------------ parenté

	List<KinView> parents(UUID uuid) {
		KinView k = kin.get(uuid);
		return k == null ? List.of() : k.parents().stream().map(kin::get).filter(p -> p != null).toList();
	}

	List<KinView> children(UUID uuid) {
		return kin.values().stream().filter(k -> k.parents().contains(uuid)).sorted(BY_BIRTH).toList();
	}

	/** Frères et sœurs, demi-frères et demi-sœurs compris. */
	List<KinView> siblings(UUID uuid) {
		KinView k = kin.get(uuid);
		if (k == null || k.parents().isEmpty()) {
			return List.of();
		}
		return kin.values().stream()
				.filter(o -> !o.uuid().equals(uuid) && o.parents().stream().anyMatch(k.parents()::contains))
				.sorted(BY_BIRTH)
				.toList();
	}

	private static final Comparator<KinView> BY_BIRTH = Comparator.comparingLong(KinView::born)
			.thenComparing(KinView::name, String.CASE_INSENSITIVE_ORDER);

	// ------------------------------------------------------------------ rendu

	void render(GuiGraphicsExtractor g, Font font, int x1, int y1, int x2, int y2, int mouseX, int mouseY) {
		nodes.clear();
		KinView me = kin.get(focus);
		if (me == null) {
			g.text(font, Component.translatable("villageboard.gui.family.unknown"), x1, y1, FADED, false);
			return;
		}
		g.text(font, Component.translatable("villageboard.gui.family.title", displayName(me))
				.withStyle(ChatFormatting.BOLD), x1, y1, INK, false);

		List<KinView> parents = parents(focus);
		List<List<KinView>> grandparents = parents.stream().map(p -> parents(p.uuid())).toList();
		boolean hasGrandparents = grandparents.stream().anyMatch(l -> !l.isEmpty());
		List<KinView> row = new ArrayList<>(siblings(focus));
		row.add(me);
		row.sort(BY_BIRTH);
		List<KinView> children = children(focus);

		int rows = (hasGrandparents ? 1 : 0) + (parents.isEmpty() ? 0 : 1) + 1 + (children.isEmpty() ? 0 : 1);
		int top = y1 + 14;
		int step = Math.clamp((y2 - top - NODE_H) / Math.max(1, rows - 1), NODE_H + 8, NODE_H + 22);
		if (rows == 1) {
			step = 0;
		}
		int width = x2 - x1;
		int widest = Math.max(Math.max(row.size(), children.size()), hasGrandparents ? 4 : 2);
		int nodeW = Math.clamp((width - (widest - 1) * GAP) / widest, 44, 84);
		int perRow = Math.max(1, (width + GAP) / (nodeW + GAP));
		int cx = (x1 + x2) / 2;
		int y = top;

		List<Node> gpNodes = new ArrayList<>();
		if (hasGrandparents) {
			List<KinView> flat = grandparents.stream().flatMap(List::stream).toList();
			gpNodes = layout(flat, null, nodeW, perRow, cx, y);
			y += step;
		}
		List<Node> parentNodes = List.of();
		if (!parents.isEmpty()) {
			parentNodes = layout(parents, null, nodeW, perRow, cx, y);
			y += step;
		}
		List<Node> rowNodes = layout(row, me, nodeW, perRow, cx, y);
		Node meNode = rowNodes.stream().filter(n -> focus.equals(n.uuid())).findFirst().orElseThrow();
		y += step;
		List<Node> childNodes = children.isEmpty() ? List.of() : layout(children, null, nodeW, perRow, cx, y);

		// Liens : grands-parents → chaque parent, parents → fratrie, villageois → enfants.
		int i = 0;
		for (int p = 0; p < parents.size(); p++) {
			int count = grandparents.get(p).size();
			List<Node> pair = gpNodes.subList(Math.min(i, gpNodes.size()), Math.min(i + count, gpNodes.size()));
			if (!pair.isEmpty() && p < parentNodes.size()) {
				connect(g, pair, List.of(parentNodes.get(p)));
			}
			i += count;
		}
		if (!parentNodes.isEmpty()) {
			connect(g, parentNodes, rowNodes);
		}
		if (!childNodes.isEmpty()) {
			connect(g, List.of(meNode), childNodes);
		}

		Node hovered = null;
		for (Node n : nodes) {
			boolean over = n.contains(mouseX, mouseY);
			if (over) {
				hovered = n;
			}
			drawNode(g, font, n, over);
		}
		if (hovered != null) {
			g.setComponentTooltipForNextFrame(font, tooltip(hovered), mouseX, mouseY);
		}
	}

	/**
	 * Place une génération centrée sur {@code cx}. Si elle ne tient pas, la dernière case devient « +n » ;
	 * {@code keep} reste visible dans tous les cas.
	 */
	private List<Node> layout(List<KinView> people, KinView keep, int nodeW, int perRow, int cx, int y) {
		List<KinView> shown = people;
		List<KinView> hidden = List.of();
		if (people.size() > perRow) {
			List<KinView> first = new ArrayList<>(people.subList(0, perRow - 1));
			if (keep != null && !first.contains(keep)) {
				first.set(first.size() - 1, keep);
			}
			shown = first;
			hidden = people.stream().filter(p -> !first.contains(p)).toList();
		}
		int count = shown.size() + (hidden.isEmpty() ? 0 : 1);
		int x = cx - (count * nodeW + (count - 1) * GAP) / 2;
		List<Node> result = new ArrayList<>();
		for (KinView k : shown) {
			result.add(new Node(x, y, nodeW, k.uuid(), List.of()));
			x += nodeW + GAP;
		}
		if (!hidden.isEmpty()) {
			result.add(new Node(x, y, nodeW, null, hidden));
		}
		nodes.addAll(result);
		return result;
	}

	/** Trait de parenté : des parents vers une barre horizontale, puis vers chaque enfant. */
	private static void connect(GuiGraphicsExtractor g, List<Node> parents, List<Node> children) {
		int bottom = parents.getFirst().y() + NODE_H;
		int topChild = children.getFirst().y();
		int mid = (bottom + topChild) / 2;
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		for (Node n : parents) {
			g.fill(n.cx(), bottom, n.cx() + 1, mid, LINE);
			minX = Math.min(minX, n.cx());
			maxX = Math.max(maxX, n.cx());
		}
		for (Node n : children) {
			g.fill(n.cx(), mid, n.cx() + 1, topChild, LINE);
			minX = Math.min(minX, n.cx());
			maxX = Math.max(maxX, n.cx());
		}
		g.fill(minX, mid, maxX + 1, mid + 1, LINE);
	}

	private void drawNode(GuiGraphicsExtractor g, Font font, Node n, boolean over) {
		int x = n.x();
		int y = n.y();
		if (n.uuid() == null) {
			g.fill(x, y, x + n.w(), y + NODE_H, over ? HOVER : EDGE);
			g.fill(x + 1, y + 1, x + n.w() - 1, y + NODE_H - 1, 0xFFE8DDB5);
			String more = "+" + n.hidden().size();
			g.text(font, more, x + n.w() / 2 - font.width(more) / 2, y + 7, FADED, false);
			return;
		}
		KinView k = kin.get(n.uuid());
		boolean gone = k.fate() != Kin.Fate.ALIVE;
		boolean isFocus = n.uuid().equals(focus);
		int border = over ? HOVER : isFocus ? GOLD : EDGE;
		int bg = isFocus ? 0xFFFBF1D6 : gone ? 0xFFDCCB9E : !k.village().isEmpty() ? 0xFFE8E0C8 : 0xFFF7ECCD;
		g.fill(x, y, x + n.w(), y + NODE_H, border);
		if (isFocus) {
			g.fill(x + 1, y + 1, x + n.w() - 1, y + NODE_H - 1, border);
			g.fill(x + 2, y + 2, x + n.w() - 2, y + NODE_H - 2, bg);
		} else {
			g.fill(x + 1, y + 1, x + n.w() - 1, y + NODE_H - 1, bg);
		}
		g.item(Texts.icon(k.baby() ? "child" : k.profession()), x + 3, y + 3);
		int textW = n.w() - 23;
		int nameColor = gone ? FADED : k.name().isEmpty() ? FADED : INK;
		g.text(font, clip(font, displayName(k), textW), x + 21, y + 3, nameColor, false);
		g.text(font, clip(font, status(k).getString(), textW), x + 21, y + 12, gone ? 0xFF9A8A6A : FADED, false);
	}

	private static String clip(Font font, String text, int width) {
		if (font.width(text) <= width) {
			return text;
		}
		String s = text;
		while (!s.isEmpty() && font.width(s + "…") > width) {
			s = s.substring(0, s.length() - 1);
		}
		return s + "…";
	}

	private static String displayName(KinView k) {
		return k.name().isEmpty() ? Component.translatable("villageboard.gui.unnamed").getString() : k.name();
	}

	/** Deuxième ligne d'une case : métier, ou ce qu'il est devenu. */
	private static Component status(KinView k) {
		return switch (k.fate()) {
			case DEAD -> Component.translatable("villageboard.gui.family.dead", k.fateDay());
			case ALIVE -> !k.village().isEmpty()
					? Component.literal("↗ " + k.village())
					: k.baby() ? Component.translatable("villageboard.gui.child") : Texts.profession(k.profession());
			default -> Component.translatable("villageboard.gui.family.fate." + k.fate().name().toLowerCase());
		};
	}

	private List<Component> tooltip(Node n) {
		List<Component> lines = new ArrayList<>();
		if (n.uuid() == null) {
			lines.add(Component.translatable("villageboard.gui.family.more", n.hidden().size()).withStyle(ChatFormatting.BOLD));
			n.hidden().stream().limit(12).forEach(k -> lines.add(Component.literal("· " + displayName(k))));
			return lines;
		}
		KinView k = kin.get(n.uuid());
		lines.add(Component.literal(displayName(k)).withStyle(ChatFormatting.BOLD));
		lines.add(k.baby() ? Component.translatable("villageboard.gui.child") : Texts.profession(k.profession()));
		lines.add(k.born() >= 0
				? Component.translatable("villageboard.gui.family.born", k.born())
				: Component.translatable("villageboard.gui.family.born_unknown"));
		if (k.fate() != Kin.Fate.ALIVE) {
			MutableComponent fate = k.fate() == Kin.Fate.DEAD
					? Component.translatable("villageboard.gui.family.dead_long", k.fateDay())
					: Component.translatable("villageboard.gui.family.fate_long." + k.fate().name().toLowerCase(), k.fateDay());
			lines.add(fate.withStyle(ChatFormatting.DARK_RED));
		} else if (!k.village().isEmpty()) {
			lines.add(Component.translatable("villageboard.gui.family.lives_in", k.village()));
		}
		List<KinView> parents = parents(k.uuid());
		if (!parents.isEmpty()) {
			lines.add(Component.translatable("villageboard.gui.family.parents_of",
					parents.stream().map(FamilyTree::displayName).collect(Collectors.joining(" & "))));
		}
		int kids = children(k.uuid()).size();
		if (kids > 0) {
			lines.add(Component.translatable("villageboard.gui.family.children_count", kids));
		}
		if (!n.uuid().equals(focus)) {
			lines.add(Component.translatable("villageboard.gui.family.click").withStyle(ChatFormatting.GRAY));
		}
		return lines;
	}

	// ------------------------------------------------------------------ entrées

	/** Clic sur une case : recentre l'arbre. @return vrai si le focus a changé */
	boolean mouseClicked(double mouseX, double mouseY) {
		for (Node n : nodes) {
			if (n.contains(mouseX, mouseY) && n.uuid() != null && !n.uuid().equals(focus)) {
				focus = n.uuid();
				return true;
			}
		}
		return false;
	}

	/** Nombre de proches connus, pour la fiche : {parents, frères et sœurs, enfants}. */
	int[] counts(UUID uuid) {
		return new int[]{parents(uuid).size(), siblings(uuid).size(), children(uuid).size()};
	}
}
