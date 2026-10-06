package fr.villageboard.client;

import com.mojang.blaze3d.platform.NativeImage;
import fr.villageboard.VillageBoard;
import fr.villageboard.block.ModBlocks;
import fr.villageboard.net.BoardView;
import fr.villageboard.net.BoardView.VillagerView;
import fr.villageboard.net.BorderView;
import fr.villageboard.village.Territory;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Carte du territoire vue du dessus, peinte comme une carte vanilla à partir des chunks chargés
 * côté client : territoire teinté et cerné, villages voisins en bleu, le reste estompé.
 * Lits libres (vert) et occupés (rouge), postes de travail libres (cyan) et occupés (violet), filtrables.
 * Survoler un habitant le relie à son lit et à son poste ; cliquer épingle cet affichage jusqu'à un clic dans le vide.
 * Molette = zoom (autour du curseur), glisser = déplacer.
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
	static final int BOUND_BED = 0xFFE0A21B;
	static final int STATION_FREE = 0xFF45B5C4;
	static final int STATION_TAKEN = 0xFF6B4E9B;
	private static final ItemStack BED_FREE_ICON = new ItemStack(Items.BED.pick(DyeColor.LIME));
	private static final ItemStack BED_TAKEN_ICON = new ItemStack(Items.BED.pick(DyeColor.RED));
	private static final int CONTROLS = 5;
	private static final int PIN_RING = 0xFFC0392B;

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
	/** Vrai dès que la souris a bougé pendant l'appui : c'est un glisser, pas un clic. */
	private boolean moved;
	private double dragMouseX;
	private double dragMouseY;
	private double dragCenterX;
	private double dragCenterZ;
	private boolean helpHover;
	private boolean filtersOpen;
	/** Élément épinglé par un clic (un seul des trois). */
	private UUID pinnedVillager;
	private BlockPos pinnedBed;
	private BlockPos pinnedStation;
	/** Zones de l'encart de l'élément épinglé, recalculées à chaque image : {x1, y1, x2, y2}. */
	private int[] infoBox;
	private int[] sheetLink;
	private VillagerView sheetPerson;
	/** Lit (BlockPos compacté) → son occupant d'après le registre. */
	private Map<Long, VillagerView> ownerByBed = Map.of();
	/** Lits attitrés (bail de logement). */
	private Set<Long> boundBeds = Set.of();
	/** Poste de travail (BlockPos compacté) → villageois qui y travaille (poste mémorisé ou attitré). */
	private Map<Long, VillagerView> workerByStation = Map.of();
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
		Set<Long> bound = new HashSet<>();
		for (VillagerView v : view.villagers()) {
			if (v.boundHome() != null) {
				owners.put(v.boundHome().asLong(), v);
				bound.add(v.boundHome().asLong());
			}
		}
		ownerByBed = owners;
		boundBeds = bound;
		Map<Long, VillagerView> workers = new HashMap<>();
		for (VillagerView v : view.villagers()) {
			if (v.jobSite() != null) {
				workers.put(v.jobSite().asLong(), v);
			}
			if (v.bound() != null) {
				workers.put(v.bound().asLong(), v);
			}
		}
		workerByStation = workers;
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

	// ------------------------------------------------------------------ filtres

	/** Types de points affichables ; la liste sert à la fois de légende et de filtre. */
	enum Layer {
		EMPLOYED(TerritoryMap.EMPLOYED, "legend.employed"),
		UNEMPLOYED(TerritoryMap.UNEMPLOYED, "legend.unemployed"),
		CHILD(TerritoryMap.CHILD, "legend.child"),
		NITWIT(TerritoryMap.NITWIT, "legend.nitwit"),
		BED_FREE(TerritoryMap.BED_FREE, "legend.bed_free"),
		BED_TAKEN(TerritoryMap.BED_TAKEN, "legend.bed_taken"),
		STATION_FREE(TerritoryMap.STATION_FREE, "legend.station_free"),
		STATION_TAKEN(TerritoryMap.STATION_TAKEN, "legend.station_taken"),
		BORNES(0xFF808080, "legend.bornes"),
		PLAYER(TerritoryMap.PLAYER, "legend.you");

		final int color;
		final String key;

		Layer(int color, String key) {
			this.color = color;
			this.key = key;
		}
	}

	/** Couches masquées ; conservées tant que le jeu tourne (même en fermant le tableau). */
	private static final EnumSet<Layer> HIDDEN = EnumSet.noneOf(Layer.class);
	private static final int FILTER_ROW = 11;
	private static final int FILTER_WIDTH = 104;

	private static boolean shown(Layer layer) {
		return !HIDDEN.contains(layer);
	}

	private static Layer layerOf(VillagerView v) {
		if (v.baby()) {
			return Layer.CHILD;
		}
		if (v.employed()) {
			return Layer.EMPLOYED;
		}
		return v.profession().endsWith(":nitwit") ? Layer.NITWIT : Layer.UNEMPLOYED;
	}

	private static boolean shown(VillagerView v) {
		return shown(layerOf(v));
	}

	private static boolean shown(BoardView.BedView bed) {
		return shown(bed.occupied() ? Layer.BED_TAKEN : Layer.BED_FREE);
	}

	private static boolean shown(BoardView.WorkstationView station) {
		return shown(station.occupied() ? Layer.STATION_TAKEN : Layer.STATION_FREE);
	}

	static int dotColor(VillagerView v) {
		return layerOf(v).color;
	}

	// ------------------------------------------------------------------ sélection

	/** Élément sous un point de la carte : un habitant (prioritaire), sinon un lit, sinon un poste. */
	private record Pick(VillagerView villager, BoardView.BedView bed, BoardView.WorkstationView station, int stacked) {
		boolean isEmpty() {
			return villager == null && bed == null && station == null;
		}
	}

	private Pick pick(double mouseX, double mouseY) {
		List<VillagerView> under = new ArrayList<>();
		for (VillagerView v : view.villagers()) {
			if (shown(v) && near(mouseX, mouseY, v.pos(), 3)) {
				under.add(v);
			}
		}
		if (!under.isEmpty()) {
			return new Pick(under.getFirst(), null, null, under.size());
		}
		for (BoardView.BedView bed : view.beds()) {
			if (shown(bed) && near(mouseX, mouseY, bed.pos(), pointRadius())) {
				return new Pick(null, bed, null, 1);
			}
		}
		for (BoardView.WorkstationView station : view.workstations()) {
			if (shown(station) && near(mouseX, mouseY, station.pos(), pointRadius())) {
				return new Pick(null, null, station, 1);
			}
		}
		return new Pick(null, null, null, 0);
	}

	/** Élément épinglé par un clic, retrouvé dans les données à jour (null s'il a disparu). */
	private Pick pinned() {
		if (pinnedVillager != null) {
			for (VillagerView v : view.villagers()) {
				if (v.uuid().equals(pinnedVillager)) {
					return new Pick(v, null, null, 1);
				}
			}
		}
		if (pinnedBed != null) {
			for (BoardView.BedView bed : view.beds()) {
				if (bed.pos().equals(pinnedBed)) {
					return new Pick(null, bed, null, 1);
				}
			}
		}
		if (pinnedStation != null) {
			for (BoardView.WorkstationView station : view.workstations()) {
				if (station.pos().equals(pinnedStation)) {
					return new Pick(null, null, station, 1);
				}
			}
		}
		return null;
	}

	/** Le villageois concerné par un élément : lui-même, l'occupant du lit ou celui qui travaille au poste. */
	private VillagerView personOf(Pick p) {
		if (p == null) {
			return null;
		}
		if (p.villager() != null) {
			return p.villager();
		}
		if (p.bed() != null) {
			return ownerByBed.get(p.bed().pos().asLong());
		}
		return p.station() == null ? null : workerByStation.get(p.station().pos().asLong());
	}

	private void togglePin(Pick p) {
		boolean same = p.villager() != null ? p.villager().uuid().equals(pinnedVillager)
				: p.bed() != null ? p.bed().pos().equals(pinnedBed)
				: p.station() != null && p.station().pos().equals(pinnedStation);
		pinnedVillager = null;
		pinnedBed = null;
		pinnedStation = null;
		if (!same) {
			pinnedVillager = p.villager() == null ? null : p.villager().uuid();
			pinnedBed = p.bed() == null ? null : p.bed().pos();
			pinnedStation = p.station() == null ? null : p.station().pos();
		}
	}

	// ------------------------------------------------------------------ rendu

	void render(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
		if (view == null || texture == null) {
			return;
		}
		if (dirty && System.currentTimeMillis() - lastBuild > REBUILD_INTERVAL_MS) {
			rebuild();
		}
		long now = System.currentTimeMillis();
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

		boolean overUi = overControls(mouseX, mouseY) || overFilters(mouseX, mouseY) || overInfo(mouseX, mouseY);
		Pick hovered = contains(mouseX, mouseY) && !overUi ? pick(mouseX, mouseY) : new Pick(null, null, null, 0);
		Pick pinned = pinned();
		VillagerView pinnedPerson = personOf(pinned);
		VillagerView hoveredPerson = personOf(hovered);
		Component tooltip = null;

		// Bornes.
		List<BlockPos> polygon = view.polygon();
		if (shown(Layer.BORNES)) {
			for (int i = 0; i < polygon.size(); i++) {
				BlockPos p = polygon.get(i);
				int sx = (int) toScreenX(p.getX() + 0.5);
				int sy = (int) toScreenY(p.getZ() + 0.5);
				icon(g, new ItemStack(ModBlocks.BOUNDARY_STONE), sx, sy, 0.5f);
				if (hovered.isEmpty() && !overUi && Math.abs(mouseX - sx) <= 4 && Math.abs(mouseY - sy) <= 4) {
					tooltip = Component.translatable("villageboard.map.borne", i + 1, p.getX(), p.getZ());
				}
			}
		}

		// Lits et postes : icônes de près, petits carrés de loin.
		boolean icons = scale >= 2.5;
		for (BoardView.BedView bed : view.beds()) {
			if (!shown(bed)) {
				continue;
			}
			int sx = (int) toScreenX(bed.pos().getX() + 0.5);
			int sy = (int) toScreenY(bed.pos().getZ() + 0.5);
			if (boundBeds.contains(bed.pos().asLong())) {
				ring(g, sx, sy, icons ? 5 : 3, BOUND_BED);
			}
			if (icons) {
				icon(g, bed.occupied() ? BED_TAKEN_ICON : BED_FREE_ICON, sx, sy, 0.5f);
			} else {
				dot(g, sx, sy, bed.occupied() ? BED_TAKEN : BED_FREE);
			}
		}
		for (BoardView.WorkstationView station : view.workstations()) {
			if (!shown(station)) {
				continue;
			}
			int sx = (int) toScreenX(station.pos().getX() + 0.5);
			int sy = (int) toScreenY(station.pos().getZ() + 0.5);
			if (icons) {
				if (!station.occupied()) {
					g.fill(sx - 5, sy - 5, sx + 5, sy + 5, 0x6645B5C4);
				}
				icon(g, Texts.icon(station.profession()), sx, sy, 0.5f);
			} else {
				dot(g, sx, sy, station.occupied() ? STATION_TAKEN : STATION_FREE);
			}
		}

		// Liens : postes attitrés (doré) en permanence ; lit (bleu) et poste (violet) de l'élément épinglé et survolé.
		for (VillagerView v : view.villagers()) {
			if (v.bound() != null && v.loaded() && shown(v)) {
				dottedLine(g, toScreenX(v.pos().getX() + 0.5), toScreenY(v.pos().getZ() + 0.5),
						toScreenX(v.bound().getX() + 0.5), toScreenY(v.bound().getZ() + 0.5), 0xCCB8860B);
			}
		}
		links(g, pinnedPerson);
		if (hoveredPerson != pinnedPerson) {
			links(g, hoveredPerson);
		}

		// Habitants.
		for (VillagerView v : view.villagers()) {
			boolean highlighted = v == pinnedPerson || v == hoveredPerson;
			if (!shown(v) && !highlighted) {
				continue;
			}
			int sx = (int) toScreenX(v.pos().getX() + 0.5);
			int sy = (int) toScreenY(v.pos().getZ() + 0.5);
			int color = dotColor(v);
			if (!v.loaded()) {
				color = (color & 0x00FFFFFF) | 0x99000000;
			}
			if (highlighted) {
				g.fill(sx - 3, sy - 3, sx + 3, sy + 3, 0xFFFFFFFF);
			}
			dot(g, sx, sy, color);
		}

		// Élément épinglé : anneau rouge autour du lit ou du poste.
		if (pinned != null && pinned.villager() == null) {
			BlockPos p = pinned.bed() != null ? pinned.bed().pos() : pinned.station().pos();
			ring(g, (int) toScreenX(p.getX() + 0.5), (int) toScreenY(p.getZ() + 0.5), icons ? 6 : 4, PIN_RING);
		} else if (pinned != null) {
			BlockPos p = pinned.villager().pos();
			ring(g, (int) toScreenX(p.getX() + 0.5), (int) toScreenY(p.getZ() + 0.5), 4, PIN_RING);
		}

		// Mairie, repère demandé depuis une fiche, joueur.
		BlockPos board = view.board();
		int bx = (int) toScreenX(board.getX() + 0.5);
		int by = (int) toScreenY(board.getZ() + 0.5);
		icon(g, new ItemStack(Items.BELL), bx, by, 0.75f);
		if (tooltip == null && hovered.isEmpty() && !overUi && Math.abs(mouseX - bx) <= 6 && Math.abs(mouseY - by) <= 6) {
			tooltip = Component.translatable("villageboard.map.board", view.name());
		}
		if (focusPos != null && now < focusUntil) {
			int r = 5 + (int) ((now / 120) % 4);
			ring(g, (int) toScreenX(focusPos.getX() + 0.5), (int) toScreenY(focusPos.getZ() + 0.5), r, PIN_RING);
		}
		if (shown(Layer.PLAYER) && mc.player != null && mc.level != null
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

		if (!hovered.isEmpty()) {
			tooltip = describe(hovered);
		}

		g.text(font, "N", x + 4, y + 3, 0xFF3B2A1A, false);
		g.fill(x + 6, y + 12, x + 7, y + 17, 0xFF3B2A1A);
		int coordsTop = y + h - 11;
		if (contains(mouseX, mouseY)) {
			String coords = Mth.floor(toWorldX(mouseX)) + ", " + Mth.floor(toWorldZ(mouseY));
			int cw = font.width(coords);
			g.fill(x + 2, coordsTop, x + 6 + cw, y + h - 2, 0xAAF3E5C0);
			g.text(font, coords, x + 4, coordsTop + 1, 0xFF3B2A1A, false);
		}
		infoPanel(g, font, pinned, mouseX, mouseY, coordsTop - 2);
		Component controlTip = controls(g, mouseX, mouseY);
		if (filtersOpen) {
			filterPanel(g, font, mouseX, mouseY);
		}

		if (helpHover) {
			g.setComponentTooltipForNextFrame(font, List.of(
					Component.translatable("villageboard.gui.map_hint").withStyle(ChatFormatting.WHITE),
					Component.translatable("villageboard.gui.map_hint.pin").withStyle(ChatFormatting.GRAY)), mouseX, mouseY);
		} else if (controlTip != null) {
			g.setTooltipForNextFrame(font, controlTip, mouseX, mouseY);
		} else if (tooltip != null) {
			g.setTooltipForNextFrame(font, tooltip, mouseX, mouseY);
		}
	}

	/** Info-bulle d'un élément : nom et métier d'un habitant, occupant d'un lit ou d'un poste. */
	private Component describe(Pick p) {
		if (p.villager() != null) {
			VillagerView v = p.villager();
			MutableComponent text = Texts.listName(v.name()).copy().append(" · ").append(
					v.baby() ? Component.translatable("villageboard.gui.child") : Texts.profession(v.profession()));
			if (v.home() == null) {
				text.append(" · ").append(Component.translatable("villageboard.gui.homeless"));
			}
			if (p.stacked() > 1) {
				text.append(Component.translatable("villageboard.map.more", p.stacked() - 1));
			}
			return text;
		}
		if (p.bed() != null) {
			VillagerView owner = ownerByBed.get(p.bed().pos().asLong());
			boolean attitre = boundBeds.contains(p.bed().pos().asLong());
			return owner != null
					? Component.translatable(attitre ? "villageboard.map.bound_bed_of" : "villageboard.map.bed_of", Texts.listName(owner.name()))
					: Component.translatable(p.bed().occupied() ? "villageboard.map.bed_taken" : "villageboard.map.bed_free");
		}
		VillagerView worker = workerByStation.get(p.station().pos().asLong());
		Component state = worker != null
				? Component.translatable("villageboard.map.station_of", Texts.listName(worker.name()))
				: Component.translatable(p.station().occupied() ? "villageboard.map.station_taken" : "villageboard.map.station_free");
		return Texts.icon(p.station().profession()).getHoverName().copy()
				.append(" · ").append(Texts.profession(p.station().profession()))
				.append(" · ").append(state);
	}

	/** Pointillés de l'habitant vers son lit (bleu) et son poste de travail (violet). */
	private void links(GuiGraphicsExtractor g, VillagerView v) {
		if (v == null) {
			return;
		}
		double sx = toScreenX(v.pos().getX() + 0.5);
		double sy = toScreenY(v.pos().getZ() + 0.5);
		if (v.home() != null) {
			dottedLine(g, sx, sy, toScreenX(v.home().getX() + 0.5), toScreenY(v.home().getZ() + 0.5), 0xFF1F4E8C);
		}
		BlockPos job = v.bound() != null ? v.bound() : v.jobSite();
		if (job != null) {
			dottedLine(g, sx, sy, toScreenX(job.getX() + 0.5), toScreenY(job.getZ() + 0.5), 0xFF6B4E9B);
		}
	}

	/** Encart de l'élément épinglé (bas gauche) : ce qu'il est, où sont son lit et son poste, lien vers la fiche. */
	private void infoPanel(GuiGraphicsExtractor g, Font font, Pick pinned, int mouseX, int mouseY, int bottom) {
		infoBox = null;
		sheetLink = null;
		if (pinned == null) {
			return;
		}
		VillagerView person = personOf(pinned);
		List<Component> lines = new ArrayList<>();
		if (pinned.villager() != null) {
			lines.add(Texts.listName(person.name()).copy().withStyle(ChatFormatting.BOLD));
			lines.add(person.baby() ? Component.translatable("villageboard.gui.child") : Texts.profession(person.profession()));
		} else {
			lines.add(describe(pinned).copy().withStyle(ChatFormatting.BOLD));
			BlockPos p = pinned.bed() != null ? pinned.bed().pos() : pinned.station().pos();
			lines.add(Component.literal(p.getX() + ", " + p.getY() + ", " + p.getZ()));
		}
		if (person != null) {
			BlockPos home = person.boundHome() != null ? person.boundHome() : person.home();
			lines.add(home != null
					? Component.translatable("villageboard.map.info.bed", home.getX() + ", " + home.getY() + ", " + home.getZ())
					: Component.translatable("villageboard.gui.cat.homeless"));
			BlockPos job = person.bound() != null ? person.bound() : person.jobSite();
			if (job != null) {
				lines.add(Component.translatable("villageboard.map.info.station", job.getX() + ", " + job.getY() + ", " + job.getZ()));
			}
		}
		Component link = person != null ? Component.translatable("villageboard.map.sheet") : null;
		int width = 0;
		for (Component c : lines) {
			width = Math.max(width, font.width(c));
		}
		if (link != null) {
			width = Math.max(width, font.width(link));
		}
		width = Math.min(width + 8, w - 8);
		int height = lines.size() * 10 + (link != null ? 10 : 0) + 6;
		int bx = x + 3;
		int by = Math.max(y + 20, bottom - height);
		g.fill(bx - 1, by - 1, bx + width + 1, by + height + 1, FRAME);
		g.fill(bx, by, bx + width, by + height, 0xEEF7ECCD);
		int ty = by + 3;
		for (Component c : lines) {
			g.text(font, c, bx + 4, ty, 0xFF3B2A1A, false);
			ty += 10;
		}
		if (link != null) {
			boolean over = mouseX >= bx + 4 && mouseX < bx + 4 + font.width(link) && mouseY >= ty - 1 && mouseY < ty + 9;
			g.text(font, link, bx + 4, ty, over ? 0xFF8B1A1A : 0xFF1F4E8C, false);
			sheetLink = new int[]{bx + 4, ty - 1, bx + 4 + font.width(link), ty + 9};
			sheetPerson = person;
		}
		infoBox = new int[]{bx, by, bx + width, by + height};
	}

	/** Boutons : zoom +, zoom −, recentrer, filtres, aide. Renvoie l'info-bulle du bouton survolé. */
	private Component controls(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		helpHover = false;
		Component tip = null;
		for (int i = 0; i < CONTROLS; i++) {
			int bx = x + w - 14;
			int by = y + 3 + i * 14;
			boolean over = mouseX >= bx && mouseX < bx + 11 && mouseY >= by && mouseY < by + 11;
			boolean active = i == 3 && (filtersOpen || !HIDDEN.isEmpty());
			g.fill(bx - 1, by - 1, bx + 12, by + 12, FRAME);
			g.fill(bx, by, bx + 11, by + 11, active ? 0xFFF2C25C : over ? 0xFFFFF4D6 : 0xFFF3E5C0);
			int ink = 0xFF3B2A1A;
			switch (i) {
				case 0 -> {
					g.fill(bx + 2, by + 5, bx + 9, by + 6, ink);
					g.fill(bx + 5, by + 2, bx + 6, by + 9, ink);
				}
				case 1 -> g.fill(bx + 2, by + 5, bx + 9, by + 6, ink);
				case 2 -> {
					g.fill(bx + 5, by + 1, bx + 6, by + 10, ink);
					g.fill(bx + 1, by + 5, bx + 10, by + 6, ink);
					g.fill(bx + 4, by + 4, bx + 7, by + 7, ink);
				}
				case 3 -> {
					g.fill(bx + 2, by + 2, bx + 9, by + 3, ink);
					g.fill(bx + 3, by + 3, bx + 8, by + 4, ink);
					g.fill(bx + 4, by + 4, bx + 7, by + 5, ink);
					g.fill(bx + 5, by + 5, bx + 6, by + 9, ink);
				}
				default -> {
					g.fill(bx + 4, by + 2, bx + 7, by + 3, ink);
					g.fill(bx + 7, by + 3, bx + 8, by + 5, ink);
					g.fill(bx + 5, by + 5, bx + 7, by + 6, ink);
					g.fill(bx + 5, by + 6, bx + 6, by + 7, ink);
					g.fill(bx + 5, by + 8, bx + 6, by + 9, ink);
				}
			}
			if (over) {
				switch (i) {
					case 0 -> tip = Component.translatable("villageboard.map.zoom_in");
					case 1 -> tip = Component.translatable("villageboard.map.zoom_out");
					case 2 -> tip = Component.translatable("villageboard.map.recenter");
					case 3 -> tip = Component.translatable("villageboard.map.filters");
					default -> helpHover = true;
				}
			}
		}
		return tip;
	}

	/** Panneau des filtres, à gauche des boutons : légende + case à cocher par type de point. */
	private void filterPanel(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
		int[] box = filterBox();
		g.fill(box[0] - 1, box[1] - 1, box[2] + 1, box[3] + 1, FRAME);
		g.fill(box[0], box[1], box[2], box[3], 0xF2F7ECCD);
		Layer[] layers = Layer.values();
		for (int i = 0; i < layers.length; i++) {
			Layer layer = layers[i];
			int ry = box[1] + 3 + i * FILTER_ROW;
			boolean over = mouseX >= box[0] && mouseX < box[2] && mouseY >= ry - 1 && mouseY < ry + FILTER_ROW - 1;
			if (over) {
				g.fill(box[0] + 1, ry - 1, box[2] - 1, ry + FILTER_ROW - 1, 0x22603A1A);
			}
			int cx = box[0] + 4;
			g.fill(cx, ry + 1, cx + 7, ry + 8, 0xFF3B2A1A);
			g.fill(cx + 1, ry + 2, cx + 6, ry + 7, 0xFFF7ECCD);
			if (shown(layer)) {
				g.fill(cx + 2, ry + 3, cx + 5, ry + 6, 0xFF3B2A1A);
			}
			g.fill(cx + 11, ry + 2, cx + 16, ry + 7, 0xFF2A1A0E);
			g.fill(cx + 12, ry + 3, cx + 15, ry + 6, layer.color);
			g.text(font, Component.translatable("villageboard.gui." + layer.key), cx + 20, ry + 1,
					shown(layer) ? 0xFF3B2A1A : 0xFF9A8A6A, false);
		}
	}

	private int[] filterBox() {
		int right = x + w - 18;
		int top = y + 3;
		return new int[]{right - FILTER_WIDTH, top, right, top + Layer.values().length * FILTER_ROW + 4};
	}

	private boolean overControls(double mouseX, double mouseY) {
		return mouseX >= x + w - 15 && mouseX < x + w - 2 && mouseY >= y + 2 && mouseY < y + 3 + CONTROLS * 14;
	}

	private boolean overFilters(double mouseX, double mouseY) {
		if (!filtersOpen) {
			return false;
		}
		int[] box = filterBox();
		return mouseX >= box[0] && mouseX < box[2] && mouseY >= box[1] && mouseY < box[3];
	}

	private boolean overInfo(double mouseX, double mouseY) {
		return infoBox != null && mouseX >= infoBox[0] && mouseX < infoBox[2] && mouseY >= infoBox[1] && mouseY < infoBox[3];
	}

	private String dimensionOfBoard() {
		return BorderDisplay.borders().stream().filter(b -> b.id().equals(view.id())).map(BorderView::dimension)
				.findFirst().orElse("");
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

	private double pointRadius() {
		return scale >= 2.5 ? 4 : 3;
	}

	private boolean near(double mouseX, double mouseY, BlockPos pos, double radius) {
		return Math.abs(mouseX - toScreenX(pos.getX() + 0.5)) <= radius
				&& Math.abs(mouseY - toScreenY(pos.getZ() + 0.5)) <= radius;
	}

	private static void dot(GuiGraphicsExtractor g, int sx, int sy, int color) {
		g.fill(sx - 2, sy - 2, sx + 2, sy + 2, 0xFF2A1A0E);
		g.fill(sx - 1, sy - 1, sx + 1, sy + 1, color);
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

	/**
	 * Clic : boutons, filtres et lien de l'encart agissent tout de suite ; sur la carte, on attend le relâchement
	 * pour distinguer un clic (épingler / relâcher un élément) d'un glisser (déplacer la carte).
	 */
	boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (!contains(mouseX, mouseY)) {
			return false;
		}
		if (overControls(mouseX, mouseY)) {
			int i = (int) ((mouseY - y - 3) / 14);
			switch (i) {
				case 0 -> zoom(1.5, x + w / 2.0, y + h / 2.0);
				case 1 -> zoom(1 / 1.5, x + w / 2.0, y + h / 2.0);
				case 2 -> fit();
				case 3 -> filtersOpen = !filtersOpen;
				default -> {
				}
			}
			return true;
		}
		if (overFilters(mouseX, mouseY)) {
			int i = (int) ((mouseY - filterBox()[1] - 2) / FILTER_ROW);
			if (i >= 0 && i < Layer.values().length) {
				Layer layer = Layer.values()[i];
				if (!HIDDEN.remove(layer)) {
					HIDDEN.add(layer);
				}
			}
			return true;
		}
		if (sheetLink != null && sheetPerson != null && mouseX >= sheetLink[0] && mouseX < sheetLink[2]
				&& mouseY >= sheetLink[1] && mouseY < sheetLink[3]) {
			onVillagerClick.accept(sheetPerson);
			return true;
		}
		if (overInfo(mouseX, mouseY)) {
			return true;
		}
		dragging = true;
		moved = false;
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
		if (Math.abs(mouseX - dragMouseX) + Math.abs(mouseY - dragMouseY) > 3) {
			moved = true;
		}
		if (moved) {
			centerX = dragCenterX - (mouseX - dragMouseX) / scale;
			centerZ = dragCenterZ - (mouseY - dragMouseY) / scale;
			dirty = true;
		}
		return true;
	}

	/** Relâchement : si la souris n'a pas bougé, c'était un clic → épingler l'élément, ou tout relâcher dans le vide. */
	void mouseReleased(double mouseX, double mouseY) {
		if (dragging && !moved) {
			Pick p = pick(mouseX, mouseY);
			if (p.isEmpty()) {
				pinnedVillager = null;
				pinnedBed = null;
				pinnedStation = null;
				filtersOpen = false;
			} else {
				togglePin(p);
			}
		}
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
