package fr.villageboard.client;

import fr.villageboard.VillageBoard;
import fr.villageboard.net.Dialogue;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Boîte de dialogue en bas de l'écran quand on parle à un villageois : sa tête, son nom, son métier et sa réplique,
 * qui s'écrit lettre par lettre. Elle disparaît après le temps de lecture, si le joueur s'éloigne, ou quand les échanges
 * s'ouvrent (second clic droit).
 * <p>
 * Pendant que la réplique s'écrit, le villageois « babille » : toujours le même son (idle3 du villageois, déclaré
 * dans assets/villageboard/sounds.json), répété toutes les quelques lettres à la hauteur de voix qui lui est propre (plus aiguë pour un
 * enfant), plus haut sur une question ou une exclamation, plus bas sur « … ». À la fin, un villageois qui a un métier
 * fait le bruit de son travail (si le serveur l'autorise). Ces sons ne sont entendus que par le joueur qui parle.
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
	/** Lettre à partir de laquelle le prochain babillage se fait entendre. */
	private static int nextBabble;
	private static boolean signed;
	private static float voice;
	/** Son du babillage : la variante idle3 du « hmm » des villageois, toujours la même. */
	private static final SoundEvent BABBLE = SoundEvent.createVariableRangeEvent(VillageBoard.id("villager.babble"));
	private static final RandomSource RANDOM = RandomSource.create();

	/** Bruit de travail de chaque métier vanilla, joué à la fin de la réplique. */
	private static final Map<String, SoundEvent> WORK_SOUNDS = Map.ofEntries(
			Map.entry("minecraft:armorer", SoundEvents.VILLAGER_WORK_ARMORER),
			Map.entry("minecraft:butcher", SoundEvents.VILLAGER_WORK_BUTCHER),
			Map.entry("minecraft:cartographer", SoundEvents.VILLAGER_WORK_CARTOGRAPHER),
			Map.entry("minecraft:cleric", SoundEvents.VILLAGER_WORK_CLERIC),
			Map.entry("minecraft:farmer", SoundEvents.VILLAGER_WORK_FARMER),
			Map.entry("minecraft:fisherman", SoundEvents.VILLAGER_WORK_FISHERMAN),
			Map.entry("minecraft:fletcher", SoundEvents.VILLAGER_WORK_FLETCHER),
			Map.entry("minecraft:leatherworker", SoundEvents.VILLAGER_WORK_LEATHERWORKER),
			Map.entry("minecraft:librarian", SoundEvents.VILLAGER_WORK_LIBRARIAN),
			Map.entry("minecraft:mason", SoundEvents.VILLAGER_WORK_MASON),
			Map.entry("minecraft:shepherd", SoundEvents.VILLAGER_WORK_SHEPHERD),
			Map.entry("minecraft:toolsmith", SoundEvents.VILLAGER_WORK_TOOLSMITH),
			Map.entry("minecraft:weaponsmith", SoundEvents.VILLAGER_WORK_WEAPONSMITH));

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
		ClientTickEvents.END_CLIENT_TICK.register(client -> babble());
	}

	private static void show(Dialogue d) {
		current = d;
		text = resolve(d);
		shownAt = System.currentTimeMillis();
		speaker = null;
		// Le serveur a déjà joué un « hmm » à l'ouverture : on attend le premier mot.
		nextBabble = 6 + RANDOM.nextInt(4);
		signed = false;
		// Chaque villageois a sa propre voix, tirée de son UUID ; celle des enfants est plus aiguë.
		voice = 0.88f + Math.floorMod(d.villager().hashCode(), 25) / 100f + (d.baby() ? 0.45f : 0f);
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

	/** Babillage pendant que la réplique s'écrit, puis bruit du métier une fois finie. */
	private static void babble() {
		Dialogue d = current;
		Minecraft mc = Minecraft.getInstance();
		if (d == null || mc.level == null || mc.player == null || mc.gui.screen() != null) {
			return;
		}
		int visible = (int) ((System.currentTimeMillis() - shownAt) * CHARS_PER_SECOND / 1000);
		if (visible < text.length()) {
			if (visible >= nextBabble) {
				// Le morceau qui vient de s'écrire décide du ton : question ou exclamation plus haut, hésitation plus bas.
				String chunk = text.substring(Math.max(0, nextBabble - 6), Math.min(text.length(), visible + 3));
				float pitch = voice;
				if (chunk.contains("?")) {
					pitch += 0.15f;
				} else if (chunk.contains("!")) {
					pitch += 0.08f;
				} else if (chunk.contains("…") || chunk.contains("...")) {
					pitch -= 0.1f;
				}
				play(mc, BABBLE, pitch + (RANDOM.nextFloat() - 0.5f) * 0.12f, 0.55f);
				nextBabble = visible + 7 + RANDOM.nextInt(6);
			}
		} else if (!signed) {
			signed = true;
			SoundEvent work = d.baby() || !d.jobSound() ? null : WORK_SOUNDS.get(d.profession());
			if (work != null) {
				play(mc, work, 1f, 0.7f);
			}
		}
	}

	/** Son joué à la position du villageois (ou du joueur si on ne le voit pas), pour ce joueur seulement. */
	private static void play(Minecraft mc, SoundEvent sound, float pitch, float volume) {
		Entity at = speaker != null && speaker.isAlive() ? speaker : mc.player;
		mc.level.playLocalSound(at.getX(), at.getY() + at.getEyeHeight(), at.getZ(), sound, SoundSource.NEUTRAL,
				volume, pitch, false);
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
