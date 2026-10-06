package fr.villageboard.client;

import fr.villageboard.village.NewsEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Traductions côté client : métiers, causes de décès, actualités. */
final class Texts {

	private static final Map<String, Item> ICONS = Map.ofEntries(
			Map.entry("armorer", Items.BLAST_FURNACE),
			Map.entry("butcher", Items.SMOKER),
			Map.entry("cartographer", Items.CARTOGRAPHY_TABLE),
			Map.entry("cleric", Items.BREWING_STAND),
			Map.entry("farmer", Items.COMPOSTER),
			Map.entry("fisherman", Items.BARREL),
			Map.entry("fletcher", Items.FLETCHING_TABLE),
			Map.entry("leatherworker", Items.CAULDRON),
			Map.entry("librarian", Items.LECTERN),
			Map.entry("mason", Items.STONECUTTER),
			Map.entry("shepherd", Items.LOOM),
			Map.entry("toolsmith", Items.SMITHING_TABLE),
			Map.entry("weaponsmith", Items.GRINDSTONE),
			Map.entry("nitwit", Items.WOOL.pick(DyeColor.GREEN)),
			Map.entry("none", Items.BED.pick(DyeColor.WHITE)),
			Map.entry("child", Items.CAKE));

	private Texts() {
	}

	/** « minecraft:librarian » → « librarian » ; « child » reste tel quel. */
	static String path(String profession) {
		int colon = profession.indexOf(':');
		return colon < 0 ? profession : profession.substring(colon + 1);
	}

	static Component profession(String profession) {
		String path = path(profession);
		String own = "villageboard.profession." + path;
		if (Language.getInstance().has(own)) {
			return Component.translatable(own);
		}
		String namespace = profession.contains(":") ? profession.substring(0, profession.indexOf(':')) : "minecraft";
		return Component.translatable("entity." + namespace + ".villager." + path);
	}

	static ItemStack icon(String profession) {
		return new ItemStack(ICONS.getOrDefault(path(profession), Items.EMERALD));
	}

	static Component level(int level) {
		return Component.translatable("merchant.level." + Math.clamp(level, 1, 5));
	}

	static Component cause(String encoded) {
		int colon = encoded.indexOf(':');
		String kind = colon < 0 ? "" : encoded.substring(0, colon);
		String value = encoded.substring(colon + 1);
		return switch (kind) {
			case "player" -> Component.literal(value);
			case "entity" -> Component.translatable(value);
			default -> Component.translatableWithFallback("villageboard.cause." + value, value.replace('_', ' '));
		};
	}

	/** Nom en milieu de phrase : « Côme » ou « un villageois sans nom ». */
	static Component who(String name) {
		return name.isEmpty() ? Component.translatable("villageboard.someone") : Component.literal(name);
	}

	/** Nom en début de phrase : « Côme » ou « Un villageois sans nom ». */
	static Component whoCap(String name) {
		return name.isEmpty() ? Component.translatable("villageboard.someone.cap") : Component.literal(name);
	}

	/** Nom dans une liste ou un titre : « Côme » ou « Sans nom » en italique. */
	static Component listName(String name) {
		return name.isEmpty()
				? Component.translatable("villageboard.gui.unnamed").withStyle(ChatFormatting.ITALIC)
				: Component.literal(name);
	}

	/** Métier en minuscules, pour le milieu d'une phrase (« devient bibliothécaire »). */
	static Component professionLower(String profession) {
		return Component.literal(profession(profession).getString().toLowerCase(Locale.ROOT));
	}

	static Component news(NewsEntry entry) {
		List<String> a = entry.args();
		String key = entry.type().translationKey();
		return switch (entry.type()) {
			case ARRIVAL, ZOMBIFIED, CURED -> Component.translatable(key, whoCap(a.get(0)));
			case MOVED_IN, MOVED_OUT -> Component.translatable(key, whoCap(a.get(0)), a.get(1));
			case LEFT, JOB, JOB_LOST, UNBOUND -> Component.translatable(key, whoCap(a.get(0)), professionLower(a.get(1)));
			case WITCH -> Component.translatable(key, who(a.get(0)));
			case DEATH -> Component.translatable(key, who(a.get(0)), professionLower(a.get(1)), cause(a.get(2)));
			case ASSIGNED -> Component.translatable(key, who(a.get(0)), professionLower(a.get(1)), a.get(2));
			case HOME_ASSIGNED -> Component.translatable(key, who(a.get(0)), a.get(1));
			case HOME_UNBOUND -> Component.translatable(key, whoCap(a.get(0)));
			case BOARD_REMOVED -> a.get(0).isEmpty()
					? Component.translatable(key + "_unknown")
					: Component.translatable(key, a.get(0));
			case RENAMED -> a.get(1).isEmpty()
					? Component.translatable(key + "_removed", whoCap(a.get(0)))
					: Component.translatable(key, whoCap(a.get(0)), a.get(1));
			case BIRTH -> {
				String parents = a.size() > 1 ? a.get(1) : "";
				if (!a.get(0).isEmpty()) {
					yield parents.isEmpty()
							? Component.translatable(key + "_unknown", a.get(0))
							: Component.translatable(key, a.get(0), parents);
				}
				yield parents.isEmpty()
						? Component.translatable(key + "_baby")
						: Component.translatable(key + "_baby_parents", parents);
			}
			default -> Component.translatable(key, a.toArray());
		};
	}

	static Component ago(long epochMillis) {
		Duration d = Duration.ofMillis(Math.max(0, System.currentTimeMillis() - epochMillis));
		if (d.toMinutes() < 1) {
			return Component.translatable("villageboard.gui.ago.now");
		}
		if (d.toHours() < 1) {
			return Component.translatable("villageboard.gui.ago.minutes", d.toMinutes());
		}
		if (d.toDays() < 1) {
			return Component.translatable("villageboard.gui.ago.hours", d.toHours());
		}
		return Component.translatable("villageboard.gui.ago.days", d.toDays());
	}
}
