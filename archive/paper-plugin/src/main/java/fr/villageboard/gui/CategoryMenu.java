package fr.villageboard.gui;

import fr.villageboard.VillageBoardPlugin;
import fr.villageboard.model.Village;
import fr.villageboard.service.Professions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Les habitants rangés par catégorie : tous, enfants, sans emploi, puis chaque métier. */
public final class CategoryMenu extends Menu {

    private static final int[] SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34};

    private final Village village;
    private final Menu parent;

    public CategoryMenu(VillageBoardPlugin plugin, Village village, Menu parent) {
        super(plugin, 5, Component.text("Habitants · " + village.name));
        this.village = village;
        this.parent = parent;
    }

    @Override
    protected void build(Player viewer) {
        set(4, Items.of(Material.VILLAGER_SPAWN_EGG, Items.title("Habitants de " + village.name), List.of(
                Items.line(village.villagers.size() + " villageois recensés"))));

        List<Professions.Category> categories = Professions.categories(village);
        for (int i = 0; i < categories.size() && i < SLOTS.length; i++) {
            Professions.Category category = categories.get(i);
            long count = village.count(category.filter());
            var item = Items.of(category.icon(),
                    Items.title(category.label(), count > 0 ? NamedTextColor.GOLD : NamedTextColor.DARK_GRAY),
                    List.of(Items.line(count == 0 ? "Personne" : count + (count > 1 ? " habitants" : " habitant")),
                            Component.empty(),
                            Items.hint("Clic : voir la liste")));
            set(SLOTS[i], item, p -> new VillagerListMenu(plugin, village, category, this).open(p));
        }

        set(36, Items.back(), parent::open);
        set(44, Items.close(), Player::closeInventory);
    }
}
