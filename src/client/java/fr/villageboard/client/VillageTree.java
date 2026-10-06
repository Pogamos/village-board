package fr.villageboard.client;

import fr.villageboard.net.BoardView;
import fr.villageboard.net.BoardView.KinView;
import fr.villageboard.net.BoardView.VillagerView;
import fr.villageboard.village.Kin;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Arbre généalogique de tout le village : un bloc par famille (ensemble de villageois reliés par la parenté ou le
 * mariage), une ligne par génération, puis les habitants sans famille connue. Chaque case montre la tête du villageois,
 * son nom et son métier. Couples : double trait rouge (mariés), trait pâle (veuvage), pointillés (divorcés).
 * Molette = zoom, glisser = déplacer, clic = fiche du villageois (ou son arbre s'il n'est plus au registre).
 */
final class VillageTree {

	private static final int NODE_W = 60;
	private static final int NODE_H = 44;
	private static final int GAP = 10;
	private static final int ROW_H = NODE_H + 34;
	private static final int FAMILY_GAP = 40;
	private static final int FACE_SCALE = 2;
	private static final int INK = 0xFF3B2A1A;
	private static final int FADED = 0xFF7A6548;
	private static final int LINE = 0xFF8A6D4A;
	private static final int MARRIED = 0xFFC2185B;
	private static final int FRAME = 0xFF5C3A1E;

	/** Un villageois de l'arbre : entrée de l'état civil, ou habitant sans famille connue. */
	private record Person(UUID uuid, String name, String profession, String type, boolean baby, Kin.Fate fate,
			long fateDay, long born, String village, List<UUID> parents, UUID spouse, List<UUID> divorced,
			List<UUID> widowed, boolean resident) {

		boolean gone() {
			return fate != Kin.Fate.ALIVE;
		}

		List<UUID> partners() {
			List<UUID> all = new ArrayList<>(divorced);
			all.addAll(widowed);
			if (spouse != null) {
				all.add(spouse);
			}
			return all;
		}
	}

	/** Position d'une case, en coordonnées de l'arbre (avant zoom et déplacement). */
	private record Placed(Person person, int x, int y) {
		int cx() {
			return x + NODE_W / 2;
		}
	}

	private final Consumer<UUID> onOpen;
	private Map<UUID, Person> people = Map.of();
	private final Map<UUID, Placed> placed = new LinkedHashMap<>();
	/** Titre du bloc des habitants sans famille : position en coordonnées de l'arbre, ou -1. */
	private int singlesTitleY = -1;
	private int contentW;
	private int contentH;
	private int x;
	private int y;
	private int w;
	private int h;
	private double offsetX;
	private double offsetY;
	private double zoom = 1;
	private boolean fitted;
	private boolean dragging;
	private boolean moved;
	private double lastX;
	private double lastY;

	VillageTree(Consumer<UUID> onOpen) {
		this.onOpen = onOpen;
	}

	void setBounds(int x, int y, int w, int h) {
		boolean changed = this.w != w || this.h != h;
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
		if (changed || !fitted) {
			fit();
		}
	}

	// ------------------------------------------------------------------ données et mise en page

	void setView(BoardView view) {
		Map<UUID, Person> map = new LinkedHashMap<>();
		Set<UUID> residents = new HashSet<>();
		view.villagers().forEach(v -> residents.add(v.uuid()));
		for (KinView k : view.family()) {
			map.put(k.uuid(), new Person(k.uuid(), k.name(), k.profession(), k.type(), k.baby(), k.fate(), k.fateDay(),
					k.born(), k.village(), k.parents(), k.spouse(), k.divorced(), k.widowed(), residents.contains(k.uuid())));
		}
		for (VillagerView v : view.villagers()) {
			map.putIfAbsent(v.uuid(), new Person(v.uuid(), v.name(), v.profession(), v.type(), v.baby(), Kin.Fate.ALIVE, 0,
					-1, "", List.of(), null, List.of(), List.of(), true));
		}
		people = map;
		layout();
	}

	/** Proches présents dans l'arbre : parents, enfants, conjoints. */
	private List<UUID> neighbours(Person p, Map<UUID, List<UUID>> children) {
		List<UUID> all = new ArrayList<>();
		p.parents().stream().filter(people::containsKey).forEach(all::add);
		p.partners().stream().filter(people::containsKey).forEach(all::add);
		all.addAll(children.getOrDefault(p.uuid(), List.of()));
		return all;
	}

	private void layout() {
		placed.clear();
		Map<UUID, List<UUID>> children = new HashMap<>();
		for (Person p : people.values()) {
			for (UUID parent : p.parents()) {
				if (people.containsKey(parent)) {
					children.computeIfAbsent(parent, k -> new ArrayList<>()).add(p.uuid());
				}
			}
		}
		Comparator<UUID> byBirth = Comparator.comparingLong((UUID u) -> people.get(u).born())
				.thenComparing(u -> people.get(u).name(), String.CASE_INSENSITIVE_ORDER);
		children.values().forEach(list -> list.sort(byBirth));

		// Familles : composantes connexes ; les villageois sans aucun lien vont dans le bloc final.
		List<List<UUID>> families = new ArrayList<>();
		List<UUID> singles = new ArrayList<>();
		Set<UUID> seen = new HashSet<>();
		List<UUID> order = new ArrayList<>(people.keySet());
		order.sort(byBirth);
		for (UUID start : order) {
			if (!seen.add(start)) {
				continue;
			}
			List<UUID> family = new ArrayList<>();
			List<UUID> stack = new ArrayList<>(List.of(start));
			while (!stack.isEmpty()) {
				UUID u = stack.removeLast();
				family.add(u);
				for (UUID n : neighbours(people.get(u), children)) {
					if (seen.add(n)) {
						stack.add(n);
					}
				}
			}
			if (family.size() == 1) {
				singles.add(start);
			} else {
				families.add(family);
			}
		}
		families.sort(Comparator.comparingInt((List<UUID> f) -> -f.size()));

		int cursorX = 0;
		int maxGen = 0;
		for (List<UUID> family : families) {
			int[] size = layoutFamily(family, children, cursorX);
			cursorX += size[0] + FAMILY_GAP;
			maxGen = Math.max(maxGen, size[1]);
		}
		int familiesW = Math.max(0, cursorX - FAMILY_GAP);

		singlesTitleY = -1;
		int bottom = families.isEmpty() ? 0 : (maxGen + 1) * ROW_H - (ROW_H - NODE_H);
		if (!singles.isEmpty()) {
			singlesTitleY = families.isEmpty() ? 0 : bottom + 16;
			int top = singlesTitleY + 14;
			int perRow = Math.max(6, (familiesW + GAP) / (NODE_W + GAP));
			singles.sort(Comparator.comparing((UUID u) -> people.get(u).name().isEmpty())
					.thenComparing(u -> people.get(u).name(), String.CASE_INSENSITIVE_ORDER)
					.thenComparing(u -> Texts.profession(people.get(u).profession()).getString()));
			for (int i = 0; i < singles.size(); i++) {
				int sx = (i % perRow) * (NODE_W + GAP);
				int sy = top + (i / perRow) * (NODE_H + GAP);
				placed.put(singles.get(i), new Placed(people.get(singles.get(i)), sx, sy));
			}
			int rows = (singles.size() + perRow - 1) / perRow;
			bottom = top + rows * (NODE_H + GAP) - GAP;
			familiesW = Math.max(familiesW, Math.min(singles.size(), perRow) * (NODE_W + GAP) - GAP);
		}
		contentW = familiesW;
		contentH = bottom;
		fitted = false;
	}

	/**
	 * Place une famille : génération = un de plus que le plus âgé des parents connus, et deux conjoints sur la même ligne.
	 * Sur chaque ligne, chacun se range sous ses parents ; un conjoint venu d'ailleurs se place à côté de son époux.
	 *
	 * @return {largeur, dernière génération}
	 */
	private int[] layoutFamily(List<UUID> family, Map<UUID, List<UUID>> children, int originX) {
		Set<UUID> members = new HashSet<>(family);
		Map<UUID, Integer> gen = new HashMap<>();
		family.forEach(u -> gen.put(u, 0));
		for (int pass = 0; pass < 100; pass++) {
			boolean changed = false;
			for (UUID u : family) {
				Person p = people.get(u);
				int g = gen.get(u);
				for (UUID parent : p.parents()) {
					if (members.contains(parent)) {
						g = Math.max(g, gen.get(parent) + 1);
					}
				}
				for (UUID partner : p.partners()) {
					if (members.contains(partner)) {
						g = Math.max(g, gen.get(partner));
					}
				}
				if (g != gen.get(u)) {
					gen.put(u, g);
					changed = true;
				}
			}
			if (!changed) {
				break;
			}
		}

		// Ordre de secours (première ligne) : parcours en profondeur depuis l'aîné, conjoints puis enfants.
		Map<UUID, Integer> dfs = new HashMap<>();
		List<UUID> roots = family.stream().filter(u -> gen.get(u) == 0)
				.sorted(Comparator.comparingLong((UUID u) -> people.get(u).born())).toList();
		for (UUID root : roots) {
			visit(root, members, children, dfs);
		}

		TreeMap<Integer, List<UUID>> rows = new TreeMap<>();
		family.forEach(u -> rows.computeIfAbsent(gen.get(u), k -> new ArrayList<>()).add(u));
		int width = 0;
		for (Map.Entry<Integer, List<UUID>> row : rows.entrySet()) {
			int rowY = row.getKey() * ROW_H;
			Map<UUID, Double> key = new HashMap<>();
			for (UUID u : row.getValue()) {
				List<Placed> ps = people.get(u).parents().stream().map(placed::get).filter(p -> p != null).toList();
				key.put(u, ps.isEmpty() ? (row.getKey() == 0 ? dfs.getOrDefault(u, 0) * (double) (NODE_W + GAP) : Double.MAX_VALUE)
						: ps.stream().mapToInt(Placed::cx).average().orElse(0) - originX);
			}
			List<UUID> sorted = new ArrayList<>(row.getValue());
			sorted.sort(Comparator.comparingDouble(key::get));
			Set<UUID> done = new HashSet<>();
			int cursor = 0;
			for (UUID u : sorted) {
				if (done.contains(u)) {
					continue;
				}
				Person p = people.get(u);
				List<UUID> unit = new ArrayList<>();
				for (UUID former : formerPartners(p)) {
					if (row.getValue().contains(former) && !done.contains(former)) {
						unit.add(former);
					}
				}
				unit.add(u);
				if (p.spouse() != null && row.getValue().contains(p.spouse()) && !done.contains(p.spouse())) {
					unit.add(p.spouse());
				}
				done.addAll(unit);
				double want = key.get(u);
				if (want != Double.MAX_VALUE) {
					// Centre l'enfant sous ses parents, sans chevaucher la case précédente.
					int index = unit.indexOf(u);
					cursor = Math.max(cursor, (int) Math.round(want - NODE_W / 2.0 - index * (NODE_W + GAP)));
				}
				for (UUID member : unit) {
					placed.put(member, new Placed(people.get(member), originX + cursor, rowY));
					cursor += NODE_W + GAP;
				}
				width = Math.max(width, cursor - GAP);
			}
		}
		return new int[]{width, rows.isEmpty() ? 0 : rows.lastKey()};
	}

	private static List<UUID> formerPartners(Person p) {
		List<UUID> all = new ArrayList<>(p.divorced());
		all.addAll(p.widowed());
		return all;
	}

	private void visit(UUID u, Set<UUID> members, Map<UUID, List<UUID>> children, Map<UUID, Integer> dfs) {
		if (!members.contains(u) || dfs.containsKey(u)) {
			return;
		}
		dfs.put(u, dfs.size());
		for (UUID partner : people.get(u).partners()) {
			visit(partner, members, children, dfs);
		}
		for (UUID child : children.getOrDefault(u, List.of())) {
			visit(child, members, children, dfs);
		}
	}

	/** Zoom et position pour voir tout l'arbre (ou le haut de l'arbre s'il est trop grand). */
	void fit() {
		if (w <= 0 || h <= 0) {
			return;
		}
		double scale = Math.min((w - 12.0) / Math.max(1, contentW), (h - 12.0) / Math.max(1, contentH));
		zoom = Mth.clamp(scale, 0.45, 1.0);
		offsetX = contentW * zoom < w ? (w - contentW * zoom) / 2 : 6;
		offsetY = contentH * zoom < h ? (h - contentH * zoom) / 2 : 6;
		fitted = true;
	}

	// ------------------------------------------------------------------ rendu

	void render(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
		g.fill(x - 1, y - 1, x + w + 1, y + h + 1, FRAME);
		g.fill(x, y, x + w, y + h, 0xFFEFE3BF);
		if (people.isEmpty()) {
			g.text(font, Component.translatable("villageboard.gui.families.empty"), x + 6, y + 6, FADED, false);
			return;
		}
		Placed hovered = null;
		if (contains(mouseX, mouseY) && !dragging) {
			double wx = (mouseX - x - offsetX) / zoom;
			double wy = (mouseY - y - offsetY) / zoom;
			for (Placed p : placed.values()) {
				if (wx >= p.x() && wx < p.x() + NODE_W && wy >= p.y() && wy < p.y() + NODE_H) {
					hovered = p;
				}
			}
		}

		g.enableScissor(x, y, x + w, y + h);
		g.pose().pushMatrix();
		g.pose().translate((float) (x + offsetX), (float) (y + offsetY));
		g.pose().scale((float) zoom, (float) zoom);
		drawCouples(g);
		drawChildren(g);
		if (singlesTitleY >= 0) {
			g.text(font, Component.translatable("villageboard.gui.families.singles").withStyle(ChatFormatting.BOLD),
					0, singlesTitleY, INK, false);
		}
		for (Placed p : placed.values()) {
			drawNode(g, font, p, p == hovered);
		}
		g.pose().popMatrix();
		g.disableScissor();

		if (hovered != null) {
			g.setComponentTooltipForNextFrame(font, tooltip(hovered.person()), mouseX, mouseY);
		}
	}

	private void drawCouples(GuiGraphicsExtractor g) {
		for (Placed a : placed.values()) {
			Person p = a.person();
			for (UUID other : p.partners()) {
				Placed b = placed.get(other);
				if (b == null || p.uuid().compareTo(other) > 0) {
					continue;
				}
				Placed left = a.x() <= b.x() ? a : b;
				Placed right = left == a ? b : a;
				int x1 = left.x() + NODE_W;
				int x2 = right.x();
				int yy = left.y() + FACE_SCALE * VillagerFace.HEIGHT / 2;
				Person q = b.person();
				if (other.equals(p.spouse()) && p.uuid().equals(q.spouse())) {
					g.fill(x1, yy - 1, x2, yy, MARRIED);
					g.fill(x1, yy + 1, x2, yy + 2, MARRIED);
				} else if (p.divorced().contains(other) || q.divorced().contains(p.uuid())) {
					for (int xx = x1; xx < x2; xx += 4) {
						g.fill(xx, yy, Math.min(xx + 2, x2), yy + 1, FADED);
					}
				} else {
					g.fill(x1, yy, x2, yy + 1, 0xFFB0A080);
				}
			}
		}
	}

	/** Parents → enfants : un trait part du milieu du couple (ou du parent seul), puis une barre au-dessus des enfants. */
	private void drawChildren(GuiGraphicsExtractor g) {
		Map<String, List<Placed>> byParents = new LinkedHashMap<>();
		for (Placed c : placed.values()) {
			List<UUID> parents = c.person().parents().stream().filter(placed::containsKey).sorted().toList();
			if (!parents.isEmpty()) {
				byParents.computeIfAbsent(parents.toString(), k -> new ArrayList<>()).add(c);
			}
		}
		int lane = 0;
		for (List<Placed> kids : byParents.values()) {
			List<Placed> parents = kids.getFirst().person().parents().stream().map(placed::get).filter(p -> p != null).toList();
			int anchorX;
			int anchorY;
			if (parents.size() >= 2 && parents.get(0).y() == parents.get(1).y()) {
				Placed left = parents.get(0).x() <= parents.get(1).x() ? parents.get(0) : parents.get(1);
				Placed right = left == parents.get(0) ? parents.get(1) : parents.get(0);
				anchorX = right.x() - GAP / 2 > left.x() + NODE_W ? right.x() - GAP / 2 : (left.cx() + right.cx()) / 2;
				anchorY = left.y() + FACE_SCALE * VillagerFace.HEIGHT / 2 + 2;
			} else {
				anchorX = parents.getFirst().cx();
				anchorY = parents.getFirst().y() + NODE_H;
			}
			int childTop = kids.stream().mapToInt(Placed::y).min().orElse(0);
			int bus = childTop - 8 - (lane++ % 3) * 4;
			int minX = anchorX;
			int maxX = anchorX;
			g.fill(anchorX, anchorY, anchorX + 1, bus, LINE);
			for (Placed c : kids) {
				g.fill(c.cx(), bus, c.cx() + 1, c.y(), LINE);
				minX = Math.min(minX, c.cx());
				maxX = Math.max(maxX, c.cx());
			}
			g.fill(minX, bus, maxX + 1, bus + 1, LINE);
		}
	}

	private void drawNode(GuiGraphicsExtractor g, Font font, Placed placedNode, boolean over) {
		Person p = placedNode.person();
		int nx = placedNode.x();
		int ny = placedNode.y();
		if (over) {
			g.fill(nx - 2, ny - 2, nx + NODE_W + 2, ny + NODE_H + 2, 0x33603A1A);
		}
		float scale = p.baby() ? 1.4f : FACE_SCALE;
		float faceW = VillagerFace.WIDTH * scale;
		float faceH = VillagerFace.HEIGHT * scale;
		float fx = nx + (NODE_W - faceW) / 2f;
		float fy = ny + (FACE_SCALE * VillagerFace.HEIGHT - faceH);
		g.fill((int) fx - 1, (int) fy - 1, (int) (fx + faceW) + 1, (int) (fy + faceH) + 1, p.resident() ? FRAME : 0xFFB0A080);
		VillagerFace.draw(g, fx, fy, scale, p.type(), p.profession(), p.gone());
		if (p.gone()) {
			g.text(font, "✝", (int) (fx + faceW) + 2, (int) fy, 0xFF8B1A1A, false);
		}

		String name = p.name().isEmpty() ? Component.translatable("villageboard.gui.unnamed").getString() : p.name();
		name = clip(font, name, NODE_W + 6);
		int nameColor = p.gone() || p.name().isEmpty() ? FADED : INK;
		g.text(font, name, nx + NODE_W / 2 - font.width(name) / 2, ny + 22, nameColor, false);

		Component job = status(p);
		g.pose().pushMatrix();
		g.pose().translate(nx + NODE_W / 2f, ny + 33);
		g.pose().scale(0.7f, 0.7f);
		String jobText = clip(font, job.getString(), (int) ((NODE_W + 8) / 0.7f));
		g.text(font, jobText, -font.width(jobText) / 2, 0, p.gone() ? 0xFF9A8A6A : FADED, false);
		g.pose().popMatrix();
	}

	private static Component status(Person p) {
		return switch (p.fate()) {
			case DEAD -> Component.translatable("villageboard.gui.family.dead", p.fateDay());
			case ALIVE -> !p.village().isEmpty()
					? Component.literal("↗ " + p.village())
					: p.baby() ? Component.translatable("villageboard.gui.child") : Texts.profession(p.profession());
			default -> Component.translatable("villageboard.gui.family.fate." + p.fate().name().toLowerCase());
		};
	}

	private List<Component> tooltip(Person p) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal(p.name().isEmpty() ? Component.translatable("villageboard.gui.unnamed").getString() : p.name())
				.withStyle(ChatFormatting.BOLD));
		lines.add(p.baby() ? Component.translatable("villageboard.gui.child") : Texts.profession(p.profession()));
		if (p.born() >= 0) {
			lines.add(Component.translatable("villageboard.gui.family.born", p.born()));
		}
		if (p.fate() == Kin.Fate.DEAD) {
			lines.add(Component.translatable("villageboard.gui.family.dead_long", p.fateDay()).withStyle(ChatFormatting.DARK_RED));
		} else if (p.gone()) {
			lines.add(Component.translatable("villageboard.gui.family.fate_long." + p.fate().name().toLowerCase(), p.fateDay())
					.withStyle(ChatFormatting.DARK_RED));
		} else if (!p.village().isEmpty()) {
			lines.add(Component.translatable("villageboard.gui.family.lives_in", p.village()));
		}
		if (p.spouse() != null && people.containsKey(p.spouse()) && !p.gone()) {
			lines.add(Component.translatable("villageboard.gui.family.spouse", nameOf(p.spouse())));
		}
		p.widowed().stream().filter(people::containsKey)
				.forEach(u -> lines.add(Component.translatable("villageboard.gui.family.widowed", nameOf(u))));
		p.divorced().stream().filter(people::containsKey)
				.forEach(u -> lines.add(Component.translatable("villageboard.gui.family.divorced", nameOf(u))));
		lines.add(Component.translatable(p.resident() ? "villageboard.gui.families.click_sheet" : "villageboard.gui.families.click_tree")
				.withStyle(ChatFormatting.GRAY));
		return lines;
	}

	private String nameOf(UUID uuid) {
		Person p = people.get(uuid);
		return p == null || p.name().isEmpty() ? Component.translatable("villageboard.gui.unnamed").getString() : p.name();
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

	// ------------------------------------------------------------------ entrées

	boolean contains(double mx, double my) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	boolean mouseClicked(double mx, double my) {
		if (!contains(mx, my)) {
			return false;
		}
		dragging = true;
		moved = false;
		lastX = mx;
		lastY = my;
		return true;
	}

	boolean mouseDragged(double mx, double my) {
		if (!dragging) {
			return false;
		}
		if (Math.abs(mx - lastX) + Math.abs(my - lastY) > 0) {
			moved = true;
		}
		offsetX += mx - lastX;
		offsetY += my - lastY;
		lastX = mx;
		lastY = my;
		return true;
	}

	/** Relâchement : sans déplacement, c'est un clic sur une case. */
	void mouseReleased(double mx, double my) {
		if (!dragging) {
			return;
		}
		dragging = false;
		if (moved) {
			return;
		}
		double wx = (mx - x - offsetX) / zoom;
		double wy = (my - y - offsetY) / zoom;
		for (Placed p : placed.values()) {
			if (wx >= p.x() && wx < p.x() + NODE_W && wy >= p.y() && wy < p.y() + NODE_H) {
				onOpen.accept(p.person().uuid());
				return;
			}
		}
	}

	boolean mouseScrolled(double mx, double my, double amount) {
		if (!contains(mx, my)) {
			return false;
		}
		double next = Mth.clamp(zoom * (amount > 0 ? 1.2 : 1 / 1.2), 0.3, 2.5);
		double wx = (mx - x - offsetX) / zoom;
		double wy = (my - y - offsetY) / zoom;
		zoom = next;
		offsetX = mx - x - wx * zoom;
		offsetY = my - y - wy * zoom;
		return true;
	}
}
