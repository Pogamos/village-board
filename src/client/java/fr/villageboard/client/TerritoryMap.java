package fr.villageboard.client;

import com.mojang.blaze3d.platform.NativeImage;
import fr.villageboard.VillageBoard;
import fr.villageboard.block.ModBlocks;
import fr.villageboard.net.BoardView;
import fr.villageboard.net.BoardView.VillagerView;
import fr.villageboard.net.BorderView;
import fr.villageboard.village.Territory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Carte du territoire vue du dessus, peinte comme une carte vanilla à partir des chunks chargés
 * côté client : territoire teinté et cerné, villages voisins en bleu, le reste estompé.
 * Lits libres (vert) et occupés (rouge) ; survoler un habitant ou un lit relie l'un à l'autre.
 * Molette = zoom (autour du curseur), glisser = déplacer, clic sur un habitant = sa fiche.
 */
final class TerritoryMap {

	private static final Identifier TEXTURE = VillageBoard.id("dynamic/territory_map");
	private static final int UNKNOWN_A = 0xFFE9DDB8;
	private static final int UNKNOWN_B = 0xFFE2D5AC;
	private static final int TERRITORY_TINT = 0xFFF2C25C;
	private static final int NEIGHBOUR_TINT = 0xFF6A8CAF;
	private static final int OUTSIDE_FADE = 0xFFE8DDB5;
	private static final int BORDER = 0xFF6B3A1A;
	private static final int FRAME = 0xFF5C3A1E;
	private static final double MIN_SCALE = 0.2;
	private static final double MAX_SCALE = 8;
	private static final long REBUILD_INTERVAL_MS = 80;

	static final int EMPLOYED = 0xFF2E9E3A;
	static final int UNEMPLOYED = 0xFF2D6CD0;
	static final int CHILD = 0xFFE0A21B;
	static final int NITWIT = 0xFF8A8A8A;
	static final int PLAYER = 0xFFFFFFFF;
	static final int BED_FREE = 0xFF7CCB5A;
	static final int BED_TAKEN = 0xFFB0413A;
	private static final ItemStack BED_FREE_ICON = new ItemStack(Items.BED.pick(DyeColor.LIME));
	private static final ItemStack BED_TAKEN_ICON = new ItemStack(Items.BED.pick(DyeColor.RED));
	private static final int CONTROLS = 4;

	private record Hover(Component text, VillagerView villager) {
	}

	private final Minecraft mc;
	private final Consumer<VillagerView> onVillagerClick;
	private BoardView view;
	private int x;
	private int y;
	private int w;
	private int h;
	private double centerX;
	private double centerZ;
	/** Pixels d'interface par bloc. */
	private double scale = 1;
	private boolean fitted;
	private boolean dirty = true;
	private DynamicTexture texture;
	private int texW;
	private int texH;
	private long lastBuild;
	private double builtCenterX;
	private double builtCenterZ;
	private double builtScale;
	private boolean dragging;
	private double dragMouseX;
	private double dragMouseY;
	private double dragCenterX;
	private double dragCenterZ;
	private Hover hover;
	private boolean helpHover;
	/** Lit (BlockPos compacté) → son occupant d'après le registre. */
	private Map<Long, VillagerView> ownerByBed = Map.of();
	private BlockPos focusPos;
	private long focusUntil;

	TerritoryMap(Minecraft mc, Consumer<VillagerView> onVillagerClick) {
		this.mc = mc;
		this.onVillagerClick = onVillagerClick;
	}

	void setView(BoardView view) {
		this.view = view;
		Map<Long, VillagerView> owners = new HashMap<>();
		for (VillagerView v : view.villagers()) {
			if (v.home() != null) {
				owners.put(v.home().asLong(), v);
			}
		}
		ownerByBed = owners;
		dirty = true;
	}

	void setBounds(int x, int y, int w, int h) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
		if (texture == null || w != texW || h != texH) {
			close();
			texture = new DynamicTexture(() -> "villageboard territory map", w, h, false);
			mc.getTextureManager().register(TEXTURE, texture);
			texW = w;
			texH = h;
		}
		if (!fitted) {
			fit();
		}
		dirty = true;
	}

	void close() {
		if (texture != null) {
			mc.getTextureManager().release(TEXTURE);
			texture = null;
		}
	}

	/** Cadre la carte sur le territoire. */
	void fit() {
		BlockPos board = view.board();
		int r = view.defaultRadius();
		double minX = board.getX() - r;
		double maxX = board.getX() + r;
		double minZ = board.getZ() - r;
		double maxZ = board.getZ() + r;
		if (view.polygon().size() >= 3) {
			minX = maxX = board.getX();
			minZ = maxZ = board.getZ();
			for (BlockPos p : view.polygon()) {
				minX = Math.min(minX, p.getX());
				maxX = Math.max(maxX, p.getX());
				minZ = Math.min(minZ, p.getZ());
				maxZ = Math.max(maxZ, p.getZ());
			}
		}
		double spanX = Math.max(16, maxX - minX);
		double spanZ = Math.max(16, maxZ - minZ);
		scale = Mth.clamp(Math.min(w / spanX, h / spanZ) * 0.85, MIN_SCALE, MAX_SCALE);
		centerX = (minX + maxX) / 2 + 0.5;
		centerZ = (minZ + maxZ) / 2 + 0.5;
		fitted = true;
		dirty = true;
	}

	private double toScreenX(double wx) {
		return x + w / 2.0 + (wx - centerX) * scale;
	}

	private double toScreenY(double wz) {
		return y + h / 2.0 + (wz - centerZ) * scale;
	}

	private double toWorldX(double sx) {
		return centerX + (sx - x - w / 2.0) / scale;
	}

	private double toWorldZ(double sy) {
		return centerZ + (sy - y - h / 2.0) / scale;
	}

	boolean contains(double mouseX, double mouseY) {
		return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
	}

	// ------------------------------------------------------------------ texture du terrain

	private void rebuild() {
		ClientLevel level = mc.level;
		if (level == null || texture == null) {
			return;
		}
		String dim = level.dimension().identifier().toString();
		List<BorderView> neighbours = BorderDisplay.borders().stream()
				.filter(b -> b.dimension().equals(dim) && !b.id().equals(view.id()))
				.toList();
		List<BlockPos> polygon = view.polygon();
		BlockPos board = view.board();
		int radius = view.defaultRadius();

		int[] wx = new int[w];
		int[] wz = new int[h];
		for (int px = 0; px < w; px++) {
			wx[px] = Mth.floor(toWorldX(x + px + 0.5));
		}
		for (int py = 0; py < h; py++) {
			wz[py] = Mth.floor(toWorldZ(y + py + 0.5));
		}
		boolean[] inside = new boolean[w * h];
		boolean[] neighbour = new boolean[w * h];
		for (int py = 0; py < h; py++) {
			for (int px = 0; px < w; px++) {
				double cx = wx[px] + 0.5;
				double cz = wz[py] + 0.5;
				int i = py * w + px;
				inside[i] = Territory.contains(polygon, board, radius, cx, cz);
				if (!inside[i]) {
					for (BorderView b : neighbours) {
						if (b.contains(dim, cx, cz)) {
							neighbour[i] = true;
							break;
						}
					}
				}
			}
		}

		NativeImage image = texture.getPixels();
		if (image == null) {
			return;
		}
		for (int py = 0; py < h; py++) {
			for (int px = 0; px < w; px++) {
				int i = py * w + px;
				boolean edge = (px + 1 < w && inside[i] != inside[i + 1]) || (py + 1 < h && inside[i] != inside[i + w]);
				int color;
				if (edge) {
					color = BORDER;
				} else {
					color = terrain(level, wx[px], wz[py]);
					if (inside[i]) {
						color = blend(color, TERRITORY_TINT, 0.22f);
					} else if (neighbour[i]) {
						color = blend(color, NEIGHBOUR_TINT, 0.35f);
					} else {
						color = blend(color, OUTSIDE_FADE, 0.5f);
					}
				}
				image.setPixel(px, py, color);
			}
		}
		texture.upload();
		builtCenterX = centerX;
		builtCenterZ = centerZ;
		builtScale = scale;
		lastBuild = System.currentTimeMillis();
		dirty = false;
	}

	/** Couleur de carte vanilla de la colonne, ombrée selon le relief (comme une carte en jeu). */
	private static int terrain(ClientLevel level, int bx, int bz) {
		if (!level.hasChunk(bx >> 4, bz >> 4)) {
			return (((bx >> 4) + (bz >> 4)) & 1) == 0 ? UNKNOWN_A : UNKNOWN_B;
		}
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz) - 1;
		MapColor color = MapColor.NONE;
		int yy = top;
		for (int i = 0; i < 12 && color == MapColor.NONE; i++, yy--) {
			pos.set(bx, yy, bz);
			color = level.getBlockState(pos).getMapColor(level, pos);
		}
		if (color == MapColor.NONE) {
			return UNKNOWN_A;
		}
		MapColor.Brightness brightness;
		if (color == MapColor.WATER) {
			int depth = 0;
			while (depth < 10 && level.getBlockState(pos.set(bx, yy - depth, bz)).getMapColor(level, pos) == MapColor.WATER) {
				depth++;
			}
			brightness = depth <= 2 ? MapColor.Brightness.HIGH : depth <= 5 ? MapColor.Brightness.NORMAL : MapColor.Brightness.LOW;
		} else {
			int north = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz - 1) - 1;
			brightness = top > north ? MapColor.Brightness.HIGH : top < north ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
		}
		return color.calculateARGBColor(brightness);
	}

	private static int blend(int a, int b, float t) {
		int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
		int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
		int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
		return 0xFF000000 | (r << 16) | (g << 8) | bl;
	}

	// ------------------------------------------------------------------ rendu

	void render(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
		if (view == null || texture == null) {
			return;
		}
		if (dirty && System.currentTimeMillis() - lastBuild > REBUILD_INTERVAL_MS) {
			rebuild();
		}
		g.fill(x - 2, y - 2, x + w + 2, y + h + 2, FRAME);
		g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFFC9AE7C);

		g.enableScissor(x, y, x + w, y + h);
		g.fill(x, y, x + w, y + h, UNKNOWN_A);
		// Pendant un déplacement, la texture précédente est simplement décalée jusqu'au prochain recalcul.
		int offX = 0;
		int offY = 0;
		if (builtScale == scale) {
			offX = (int) Math.round((builtCenterX - centerX) * scale);
			offY = (int) Math.round((builtCenterZ - centerZ) * scale);
		}
		g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x + offX, y + offY, 0, 0, w, h, w, h);

		hover = null;
		helpHover = false;
		long now = System.currentTimeMillis();

		// 1) Ce qui est sous la souris (habitant, sinon lit), pour savoir quels liens tracer.
		List<VillagerView> under = new ArrayList<>();
		for (VillagerView v : view.villagers()) {
			if (near(mouseX, mouseY, v.pos(), 3)) {
				under.add(v);
			}
		}
		BoardView.BedView hoveredBed = null;
		if (under.isEmpty()) {
			for (BoardView.BedView bed : view.beds()) {
				if (near(mouseX, mouseY, bed.pos(), bedRadius())) {
					hoveredBed = bed;
					break;
				}
			}
		}
		VillagerView linked = !under.isEmpty() ? under.getFirst()
				: hoveredBed != null ? ownerByBed.get(hoveredBed.pos().asLong()) : null;

		// 2) Bornes.
		List<BlockPos> polygon = view.polygon();
		for (int i = 0; i < polygon.size(); i++) {
			BlockPos p = polygon.get(i);
			int sx = (int) toScreenX(p.getX() + 0.5);
			int sy = (int) toScreenY(p.getZ() + 0.5);
			icon(g, new ItemStack(ModBlocks.BOUNDARY_STONE), sx, sy, 0.5f);
			if (Math.abs(mouseX - sx) <= 4 && Math.abs(mouseY - sy) <= 4) {
				hover = new Hover(Component.translatable("villageboard.map.borne", i + 1, p.getX(), p.getZ()), null);
			}
		}

		// 3) Lits : petits carrés de loin, icônes de lit de près.
		boolean bedIcons = scale >= 2.5;
		for (BoardView.BedView bed : view.beds()) {
			int sx = (int) toScreenX(bed.pos().getX() + 0.5);
			int sy = (int) toScreenY(bed.pos().getZ() + 0.5);
			if (bedIcons) {
				icon(g, bed.occupied() ? BED_TAKEN_ICON : BED_FREE_ICON, sx, sy, 0.5f);
			} else {
				g.fill(sx - 2, sy - 2, sx + 2, sy + 2, 0xFF2A1A0E);
				g.fill(sx - 1, sy - 1, sx + 1, sy + 1, bed.occupied() ? BED_TAKEN : BED_FREE);
			}
		}
		if (hoveredBed != null) {
			VillagerView owner = ownerByBed.get(hoveredBed.pos().asLong());
			Component text = owner != null
					? Component.translatable("villageboard.map.bed_of", Texts.listName(owner.name()))
					: Component.translatable(hoveredBed.occupied() ? "villageboard.map.bed_taken" : "villageboard.map.bed_free");
			hover = new Hover(text, owner);
		}

		// 4) Liens : postes attitrés (doré), lit de l'habitant survolé (bleu).
		for (VillagerView v : view.villagers()) {
			if (v.bound() != null && v.loaded()) {
				dottedLine(g, toScreenX(v.pos().getX() + 0.5), toScreenY(v.pos().getZ() + 0.5),
						toScreenX(v.bound().getX() + 0.5), toScreenY(v.bound().getZ() + 0.5), 0xCCB8860B);
			}
		}
		if (linked != null && linked.home() != null) {
			dottedLine(g, toScreenX(linked.pos().getX() + 0.5), toScreenY(linked.pos().getZ() + 0.5),
					toScreenX(linked.home().getX() + 0.5), toScreenY(linked.home().getZ() + 0.5), 0xFF1F4E8C);
		}

		// 5) Habitants.
		for (VillagerView v : view.villagers()) {
			int sx = (int) toScreenX(v.pos().getX() + 0.5);
			int sy = (int) toScreenY(v.pos().getZ() + 0.5);
			int color = dotColor(v);
			if (!v.loaded()) {
				color = (color & 0x00FFFFFF) | 0x99000000;
			}
			if (v == linked) {
				g.fill(sx - 3, sy - 3, sx + 3, sy + 3, 0xFFFFFFFF);
			}
			g.fill(sx - 2, sy - 2, sx + 2, sy + 2, 0xFF2A1A0E);
			g.fill(sx - 1, sy - 1, sx + 1, sy + 1, color);
		}

		// 6) Mairie, repère demandé depuis une fiche, joueur.
		BlockPos board = view.board();
		int bx = (int) toScreenX(board.getX() + 0.5);
		int by = (int) toScreenY(board.getZ() + 0.5);
		icon(g, new ItemStack(Items.BELL), bx, by, 0.75f);
		if (hover == null && Math.abs(mouseX - bx) <= 6 && Math.abs(mouseY - by) <= 6) {
			hover = new Hover(Component.translatable("villageboard.map.board", view.name()), null);
		}
		if (focusPos != null && now < focusUntil) {
			int fx = (int) toScreenX(focusPos.getX() + 0.5);
			int fy = (int) toScreenY(focusPos.getZ() + 0.5);
			int r = 5 + (int) ((now / 120) % 4);
			ring(g, fx, fy, r, 0xFFC0392B);
		}
		if (mc.player != null && mc.level != null
				&& mc.level.dimension().identifier().toString().equals(dimensionOfBoard())) {
			int px = (int) toScreenX(mc.player.getX());
			int py = (int) toScreenY(mc.player.getZ());
			g.fill(px - 3, py - 3, px + 3, py + 3, 0xFF000000);
			g.fill(px - 2, py - 2, px + 2, py + 2, PLAYER);
			float yaw = mc.player.getYRot() * Mth.DEG_TO_RAD;
			int tx = px + Math.round(-Mth.sin(yaw) * 6);
			int ty = py + Math.round(Mth.cos(yaw) * 6);
			g.fill(tx - 1, ty - 1, tx + 1, ty + 1, 0xFF000000);
		}
		g.disableScissor();

		if (!under.isEmpty()) {
			VillagerView v = under.getFirst();
			Component text = Texts.listName(v.name()).copy().append(" · ").append(
					v.baby() ? Component.translatable("villageboard.gui.child") : Texts.profession(v.profession()));
			if (v.home() == null) {
				text = text.copy().append(" · ").append(Component.translatable("villageboard.gui.homeless"));
			}
			if (under.size() > 1) {
				text = text.copy().append(Component.translatable("villageboard.map.more", under.size() - 1));
			}
			hover = new Hover(text, v);
		}

		controls(g, font, mouseX, mouseY);
		g.text(font, "N", x + 4, y + 3, 0xFF3B2A1A, false);
		g.fill(x + 6, y + 12, x + 7, y + 17, 0xFF3B2A1A);
		if (contains(mouseX, mouseY)) {
			String coords = Mth.floor(toWorldX(mouseX)) + ", " + Mth.floor(toWorldZ(mouseY));
			int cw = font.width(coords);
			g.fill(x + 2, y + h - 11, x + 6 + cw, y + h - 2, 0xAAF3E5C0);
			g.text(font, coords, x + 4, y + h - 10, 0xFF3B2A1A, false);
		}
		if (helpHover) {
			g.setComponentTooltipForNextFrame(font, legend(), mouseX, mouseY);
		} else if (hover != null) {
			g.setTooltipForNextFrame(font, hover.text(), mouseX, mouseY);
		}
	}

	private String dimensionOfBoard() {
		return BorderDisplay.borders().stream().filter(b -> b.id().equals(view.id())).map(BorderView::dimension)
				.findFirst().orElse("");
	}

	static int dotColor(VillagerView v) {
		if (v.baby()) {
			return CHILD;
		}
		if (v.employed()) {
			return EMPLOYED;
		}
		return v.profession().endsWith(":nitwit") ? NITWIT : UNEMPLOYED;
	}

	/** Boutons zoom +, zoom −, recentrer et aide (coin supérieur droit). */
	private void controls(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
		String[] labels = {"+", "-", "", "?"};
		for (int i = 0; i < CONTROLS; i++) {
			int bx = x + w - 14;
			int by = y + 3 + i * 14;
			boolean over = mouseX >= bx && mouseX < bx + 11 && mouseY >= by && mouseY < by + 11;
			g.fill(bx - 1, by - 1, bx + 12, by + 12, FRAME);
			g.fill(bx, by, bx + 11, by + 11, over ? 0xFFFFF4D6 : 0xFFF3E5C0);
			if (i == 2) {
				g.fill(bx + 5, by + 2, bx + 6, by + 9, 0xFF3B2A1A);
				g.fill(bx + 2, by + 5, bx + 9, by + 6, 0xFF3B2A1A);
			} else {
				g.text(font, labels[i], bx + 6 - font.width(labels[i]) / 2, by + 2, 0xFF3B2A1A, false);
			}
			if (over && i == 2) {
				hover = new Hover(Component.translatable("villageboard.map.recenter"), null);
			} else if (over && i == 3) {
				helpHover = true;
			}
		}
	}

	/** Légende et mode d'emploi, affichés au survol du bouton « ? ». */
	private static List<Component> legend() {
		return List.of(
				legendLine(EMPLOYED, "legend.employed"),
				legendLine(UNEMPLOYED, "legend.unemployed"),
				legendLine(CHILD, "legend.child"),
				legendLine(NITWIT, "legend.nitwit"),
				legendLine(BED_FREE, "legend.bed_free"),
				legendLine(BED_TAKEN, "legend.bed_taken"),
				legendLine(PLAYER, "legend.you"),
				Component.empty(),
				Component.translatable("villageboard.gui.map_hint").withStyle(net.minecraft.ChatFormatting.GRAY));
	}

	private static Component legendLine(int color, String key) {
		return Component.literal("■ ").withColor(color & 0xFFFFFF)
				.append(Component.translatable("villageboard.gui." + key).withStyle(net.minecraft.ChatFormatting.WHITE));
	}

	/** Centre la carte sur un point et l'y signale quelques secondes (lien « voir sur la carte »). */
	void focus(BlockPos pos) {
		centerX = pos.getX() + 0.5;
		centerZ = pos.getZ() + 0.5;
		scale = Math.max(scale, 3);
		fitted = true;
		focusPos = pos;
		focusUntil = System.currentTimeMillis() + 4000;
		dirty = true;
	}

	private double bedRadius() {
		return scale >= 2.5 ? 4 : 3;
	}

	private boolean near(int mouseX, int mouseY, BlockPos pos, double radius) {
		return Math.abs(mouseX - toScreenX(pos.getX() + 0.5)) <= radius
				&& Math.abs(mouseY - toScreenY(pos.getZ() + 0.5)) <= radius;
	}

	private static void ring(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
		g.fill(cx - r, cy - r, cx + r + 1, cy - r + 1, color);
		g.fill(cx - r, cy + r, cx + r + 1, cy + r + 1, color);
		g.fill(cx - r, cy - r, cx - r + 1, cy + r + 1, color);
		g.fill(cx + r, cy - r, cx + r + 1, cy + r + 1, color);
	}

	private static void icon(GuiGraphicsExtractor g, ItemStack stack, int sx, int sy, float size) {
		g.pose().pushMatrix();
		g.pose().translate(sx, sy);
		g.pose().scale(size, size);
		g.item(stack, -8, -8);
		g.pose().popMatrix();
	}

	private static void dottedLine(GuiGraphicsExtractor g, double x1, double y1, double x2, double y2, int color) {
		double length = Math.hypot(x2 - x1, y2 - y1);
		int steps = (int) (length / 3);
		for (int s = 0; s <= steps; s++) {
			double t = steps == 0 ? 0 : (double) s / steps;
			int px = (int) (x1 + (x2 - x1) * t);
			int py = (int) (y1 + (y2 - y1) * t);
			g.fill(px, py, px + 1, py + 1, color);
		}
	}

	// ------------------------------------------------------------------ entrées

	boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (!contains(mouseX, mouseY)) {
			return false;
		}
		int bx = x + w - 14;
		for (int i = 0; i < CONTROLS; i++) {
			int by = y + 3 + i * 14;
			if (mouseX >= bx && mouseX < bx + 11 && mouseY >= by && mouseY < by + 11) {
				switch (i) {
					case 0 -> zoom(1.5, x + w / 2.0, y + h / 2.0);
					case 1 -> zoom(1 / 1.5, x + w / 2.0, y + h / 2.0);
					case 2 -> fit();
					default -> {
					}
				}
				return true;
			}
		}
		if (button == 0 && hover != null && hover.villager() != null) {
			onVillagerClick.accept(hover.villager());
			return true;
		}
		dragging = true;
		dragMouseX = mouseX;
		dragMouseY = mouseY;
		dragCenterX = centerX;
		dragCenterZ = centerZ;
		return true;
	}

	boolean mouseDragged(double mouseX, double mouseY) {
		if (!dragging) {
			return false;
		}
		centerX = dragCenterX - (mouseX - dragMouseX) / scale;
		centerZ = dragCenterZ - (mouseY - dragMouseY) / scale;
		dirty = true;
		return true;
	}

	void mouseReleased() {
		dragging = false;
	}

	boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
		if (!contains(mouseX, mouseY) || scrollY == 0) {
			return false;
		}
		zoom(scrollY > 0 ? 1.25 : 0.8, mouseX, mouseY);
		return true;
	}

	/** Zoom en gardant fixe le point du monde sous (sx, sy). */
	private void zoom(double factor, double sx, double sy) {
		double wx = toWorldX(sx);
		double wz = toWorldZ(sy);
		scale = Mth.clamp(scale * factor, MIN_SCALE, MAX_SCALE);
		centerX = wx - (sx - x - w / 2.0) / scale;
		centerZ = wz - (sy - y - h / 2.0) / scale;
		dirty = true;
	}
}
