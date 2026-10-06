package fr.villageboard.gui;

import fr.villageboard.Text;
import fr.villageboard.VillageBoardPlugin;
import fr.villageboard.model.NewsEntry;
import fr.villageboard.model.Village;
import fr.villageboard.model.VillagerRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Accueil du tableau : statistiques, actualités, habitants. */
public final class MainMenu extends Menu {

    private final Village village;

    public MainMenu(VillageBoardPlugin plugin, Village village) {
        super(plugin, 3, Component.text("Mairie · " + village.name));
        this.village = village;
    }

    @Override
    protected void build(Player viewer) {
        long total = village.villagers.size();
        long employed = village.count(VillagerRecord::employed);
        long children = village.count(r -> r.baby);
        long unemployed = village.count(r -> !r.baby && r.profession.equals("none"));

        set(4, Items.of(Material.BELL, Items.title("Mairie de " + village.name), List.of(
                Items.line("Population : " + total, NamedTextColor.WHITE),
                Items.line("Adultes en poste : " + employed),
                Items.line("Sans emploi : " + unemployed),
                Items.line("Enfants : " + children),
                Component.empty(),
                Items.line("Jour " + village.currentDay() + " · rayon " + village.radius + " blocs", NamedTextColor.DARK_GRAY))));

        List<Component> newsLore = new ArrayList<>();
        if (village.news.isEmpty()) {
            newsLore.add(Items.line("Rien à signaler pour l'instant."));
        }
        village.news.stream().limit(5).forEach(n -> newsLore.add(newsLine(n)));
        newsLore.add(Component.empty());
        newsLore.add(Items.hint("Clic : lire la gazette"));
        set(11, Items.of(Material.WRITABLE_BOOK, Items.title("Actualités"), newsLore), p -> {
            p.closeInventory();
            NewsBook.open(p, village);
        });

        set(15, Items.of(Material.VILLAGER_SPAWN_EGG, Items.title("Habitants"), List.of(
                Items.line(total + " villageois, rangés par métier"),
                Component.empty(),
                Items.hint("Clic : consulter la liste"))),
                p -> new CategoryMenu(plugin, village, this).open(p));

        set(22, Items.close(), Player::closeInventory);
    }

    private static Component newsLine(NewsEntry n) {
        return Component.text(n.type().symbol + " ", n.type().color)
                .append(Component.text(Text.truncate(n.text(), 48), NamedTextColor.GRAY));
    }
}
