package fr.villageboard.gui;

import fr.villageboard.Text;
import fr.villageboard.VillageBoardPlugin;
import fr.villageboard.model.Village;
import fr.villageboard.model.VillagerRecord;
import fr.villageboard.service.Professions;
import fr.villageboard.service.VillagerActions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Liste paginée des villageois d'une catégorie. */
public final class VillagerListMenu extends Menu {

    private static final int PER_PAGE = 45;

    private final Village village;
    private final Professions.Category category;
    private final Menu parent;
    private int page;

    public VillagerListMenu(VillageBoardPlugin plugin, Village village, Professions.Category category, Menu parent) {
        super(plugin, 6, Component.text(village.name + " · " + category.label()));
        this.village = village;
        this.category = category;
        this.parent = parent;
    }

    @Override
    protected void build(Player viewer) {
        Collator collator = Collator.getInstance(Locale.FRENCH);
        List<VillagerRecord> list = village.villagers.values().stream()
                .filter(category.filter())
                .sorted(Comparator.comparing(VillagerRecord::displayName, collator))
                .toList();
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.clamp(page, 0, pages - 1);

        for (int i = 0; i < PER_PAGE; i++) {
            int index = page * PER_PAGE + i;
            if (index >= list.size()) {
                break;
            }
            VillagerRecord r = list.get(index);
            set(i, entry(r, viewer), p -> new VillagerSheetMenu(plugin, village, r.uuid, this).open(p));
        }
        if (list.isEmpty()) {
            set(22, Items.of(Material.PAPER, Items.title("Personne ici", NamedTextColor.GRAY), List.of()));
        }

        set(45, Items.back(), parent::open);
        if (page > 0) {
            set(48, Items.of(Material.SPECTRAL_ARROW, Items.title("Page précédente", NamedTextColor.WHITE), List.of()), p -> {
                page--;
                render(p);
            });
        }
        set(49, Items.of(Material.PAPER, Items.title("Page " + (page + 1) + " / " + pages, NamedTextColor.WHITE),
                List.of(Items.line(list.size() + " villageois"))));
        if (page < pages - 1) {
            set(50, Items.of(Material.SPECTRAL_ARROW, Items.title("Page suivante", NamedTextColor.WHITE), List.of()), p -> {
                page++;
                render(p);
            });
        }
        set(53, Items.close(), Player::closeInventory);
    }

    private static ItemStack entry(VillagerRecord r, Player viewer) {
        boolean loaded = VillagerActions.live(r.uuid) != null;
        List<Component> lore = new ArrayList<>();
        lore.add(Items.line(Professions.describe(r), NamedTextColor.AQUA));
        Location loc = r.location();
        if (loc != null && loc.getWorld().equals(viewer.getWorld())) {
            lore.add(Items.line("À " + (int) loc.distance(viewer.getLocation()) + " blocs (" + Text.coords(loc) + ")"));
        } else if (loc != null) {
            lore.add(Items.line(loc.getWorld().getName() + " (" + Text.coords(loc) + ")"));
        }
        if (r.locked) {
            lore.add(Items.line("■ Métier verrouillé", NamedTextColor.GOLD));
        }
        if (!loaded) {
            lore.add(Items.line("Hors de portée · vu " + Text.ago(r.lastSeen), NamedTextColor.DARK_GRAY));
        }
        lore.add(Component.empty());
        lore.add(Items.hint("Clic : ouvrir la fiche"));
        ItemStack item = Items.of(Professions.icon(r),
                Items.title(r.displayName(), loaded ? NamedTextColor.YELLOW : NamedTextColor.GRAY), lore);
        return r.locked ? Items.glint(item) : item;
    }
}
