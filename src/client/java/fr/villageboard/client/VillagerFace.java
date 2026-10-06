package fr.villageboard.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Visage d'un villageois dessiné d'après les textures vanilla : peau, vêtements de son biome, puis tenue de son métier
 * (chapeau compris) et nez. Fonctionne aussi pour les morts et les absents, qui n'ont plus d'entité à afficher.
 * Textures 64×64 : face de la tête en (8, 8), 8×10 ; chapeau en (40, 8) ; avant du nez en (26, 2), 2×4.
 */
final class VillagerFace {

	static final int WIDTH = 8;
	static final int HEIGHT = 10;
	private static final Identifier BASE = Identifier.withDefaultNamespace("textures/entity/villager/villager.png");

	private VillagerFace() {
	}

	/**
	 * @param scale 1 = 8×10 pixels d'interface
	 * @param gone  mort ou parti : visage pâli
	 */
	static void draw(GuiGraphicsExtractor g, float x, float y, float scale, String type, String profession, boolean gone) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		face(g, BASE);
		Identifier biome = texture("type", type);
		if (biome != null) {
			face(g, biome);
		}
		Identifier job = profession.endsWith(":none") ? null : texture("profession", profession);
		if (job != null) {
			face(g, job);
		}
		hat(g, BASE);
		if (biome != null) {
			hat(g, biome);
		}
		if (job != null) {
			hat(g, job);
		}
		g.blit(RenderPipelines.GUI_TEXTURED, BASE, 3, 7, 26, 2, 2, 3, 64, 64);
		if (gone) {
			g.fill(0, 0, WIDTH, HEIGHT, 0x99E8DDB5);
		}
		g.pose().popMatrix();
	}

	private static void face(GuiGraphicsExtractor g, Identifier id) {
		g.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, 8, 8, WIDTH, HEIGHT, 64, 64);
	}

	private static void hat(GuiGraphicsExtractor g, Identifier id) {
		g.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, 40, 8, WIDTH, HEIGHT, 64, 64);
	}

	/** Texture vanilla du type ou du métier ; null pour un identifiant d'un autre mod (texture inconnue). */
	private static Identifier texture(String folder, String id) {
		int colon = id.indexOf(':');
		String namespace = colon < 0 ? "minecraft" : id.substring(0, colon);
		if (!namespace.equals("minecraft")) {
			return null;
		}
		return Identifier.withDefaultNamespace("textures/entity/villager/" + folder + "/" + id.substring(colon + 1) + ".png");
	}
}
