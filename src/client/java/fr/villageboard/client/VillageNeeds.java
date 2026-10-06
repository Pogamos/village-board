package fr.villageboard.client;

import fr.villageboard.item.ModItems;
import fr.villageboard.net.BoardView;
import fr.villageboard.net.BoardView.VillagerView;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Besoins du village, déduits côté client des données du tableau (habitants, lits, postes, cloches, golems).
 * Chaque besoin a une gravité, un titre, une explication, des icônes (souvent : quoi construire) et une cible
 * vers laquelle le tableau emmène quand on clique dessus.
 */
final class VillageNeeds {

	enum Severity {
		URGENT(0xFFB71C1C),
		ADVICE(0xFFC77700),
		INFO(0xFF8A7A5A);

		final int color;

		Severity(int color) {
			this.color = color;
		}
	}

	/** Où mène un clic sur le besoin. */
	enum Target {HOMELESS, UNEMPLOYED, MAP, NONE}

	record Need(Severity severity, Component title, Component detail, List<ItemStack> icons, Target target) {
	}

	/** Métiers vanilla, du plus utile au moins utile pour un village (ordre des suggestions). */
	static final List<String> JOBS = List.of(
			"minecraft:farmer", "minecraft:librarian", "minecraft:cleric", "minecraft:armorer",
			"minecraft:toolsmith", "minecraft:weaponsmith", "minecraft:fletcher", "minecraft:butcher",
			"minecraft:fisherman", "minecraft:shepherd", "minecraft:leatherworker", "minecraft:mason",
			"minecraft:cartographer");

	private static final int MAX_SUGGESTIONS = 6;

	private VillageNeeds() {
	}

	static List<Need> compute(BoardView view) {
		List<VillagerView> villagers = view.villagers();
		long adults = villagers.stream().filter(v -> !v.baby()).count();
		long homeless = villagers.stream().filter(v -> v.home() == null).count();
		long freeBeds = view.beds().stream().filter(b -> !b.occupied()).count();
		long unemployed = villagers.stream().filter(v -> !v.baby() && v.profession().endsWith(":none")).count();
		long freeStations = view.workstations().stream().filter(w -> !w.occupied()).count();
		Set<String> practised = villagers.stream().filter(VillagerView::employed).map(VillagerView::profession)
				.collect(Collectors.toSet());
		Set<String> equipped = view.workstations().stream().map(BoardView.WorkstationView::profession)
				.collect(Collectors.toSet());
		List<Need> needs = new ArrayList<>();

		// Logement : sans lit libre, aucune naissance n'est possible.
		ItemStack bed = new ItemStack(Items.BED.pick(DyeColor.RED));
		if (freeBeds == 0 && homeless > 0) {
			needs.add(new Need(Severity.URGENT, need("homeless_no_bed", homeless), need("homeless_no_bed.detail", homeless),
					List.of(bed), Target.HOMELESS));
		} else if (freeBeds == 0) {
			needs.add(new Need(Severity.ADVICE, need("no_free_bed"), need("no_free_bed.detail"), List.of(bed), Target.MAP));
		} else if (homeless > 0) {
			needs.add(new Need(Severity.INFO, need("homeless_free_bed", homeless, freeBeds), need("homeless_free_bed.detail"),
					List.of(bed), Target.HOMELESS));
		}

		// Travail.
		if (unemployed > 0 && freeStations == 0) {
			List<ItemStack> suggestions = JOBS.stream()
					.sorted(Comparator.comparing((String p) -> practised.contains(p) || equipped.contains(p)))
					.limit(MAX_SUGGESTIONS)
					.map(Texts::icon)
					.toList();
			needs.add(new Need(Severity.ADVICE, need("unemployed_no_station", unemployed), need("unemployed_no_station.detail"),
					suggestions, Target.UNEMPLOYED));
		} else if (unemployed > 0) {
			needs.add(new Need(Severity.INFO, need("unemployed_free_station", unemployed, freeStations),
					need("unemployed_free_station.detail"), List.of(new ItemStack(ModItems.WORK_CONTRACT)),
					Target.UNEMPLOYED));
		} else if (freeStations > 0) {
			needs.add(new Need(Severity.INFO, need("free_stations", freeStations), need("free_stations.detail"),
					List.of(), Target.MAP));
		}

		// Nourriture : les fermiers récoltent et partagent la nourriture nécessaire à la reproduction.
		if (adults >= 2 && !practised.contains("minecraft:farmer")) {
			boolean composter = equipped.contains("minecraft:farmer");
			needs.add(new Need(Severity.ADVICE, need("no_farmer"),
					need(composter ? "no_farmer.detail_composter" : "no_farmer.detail"),
					List.of(new ItemStack(Items.COMPOSTER), new ItemStack(Items.WHEAT)), Target.MAP));
		}

		// Défense.
		if (villagers.size() >= 5 && view.golems() == 0) {
			needs.add(new Need(Severity.ADVICE, need("no_golem"), need("no_golem.detail"),
					List.of(new ItemStack(Items.IRON_BLOCK), new ItemStack(Items.CARVED_PUMPKIN)), Target.NONE));
		}

		// Lieu de rassemblement.
		if (view.bells() == 0) {
			needs.add(new Need(Severity.INFO, need("no_bell"), need("no_bell.detail"), List.of(new ItemStack(Items.BELL)), Target.MAP));
		}

		// Métiers absents : ni exercés, ni de poste construit.
		List<String> missing = JOBS.stream().filter(p -> !practised.contains(p) && !equipped.contains(p)).toList();
		if (!missing.isEmpty() && adults > 0) {
			Component names = Component.literal(missing.stream()
					.map(p -> Texts.profession(p).getString())
					.collect(Collectors.joining(", ")));
			needs.add(new Need(Severity.INFO, need("missing_jobs", missing.size()), need("missing_jobs.detail", names),
					missing.stream().map(Texts::icon).toList(), Target.MAP));
		}

		needs.sort(Comparator.comparing(Need::severity));
		return needs;
	}

	/** Besoins qui méritent l'attention (urgents et conseils) : affichés sur l'onglet. */
	static long important(List<Need> needs) {
		return needs.stream().filter(n -> n.severity() != Severity.INFO).count();
	}

	private static Component need(String key, Object... args) {
		return Component.translatable("villageboard.need." + key, args);
	}
}
