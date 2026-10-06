package fr.villageboard.gui;

import fr.villageboard.model.NewsEntry;
import fr.villageboard.model.Village;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** La gazette du village : les actualités dans un livre, de la plus récente à la plus ancienne. */
final class NewsBook {

    private static final int LINES_PER_PAGE = 14;
    private static final int CHARS_PER_LINE = 18;
    private static final int MAX_PAGES = 100;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private NewsBook() {
    }

    static void open(Player player, Village village) {
        List<Component> pages = new ArrayList<>();
        pages.add(Component.text()
                .append(Component.text("La Gazette\n", NamedTextColor.DARK_RED, TextDecoration.BOLD))
                .append(Component.text("de " + village.name + "\n\n", NamedTextColor.DARK_RED))
                .append(Component.text("Jour " + village.currentDay() + "\n", NamedTextColor.BLACK))
                .append(Component.text(village.villagers.size() + " habitants\n\n", NamedTextColor.BLACK))
                .append(Component.text(village.news.isEmpty()
                        ? "Aucune nouvelle pour l'instant."
                        : "Tournez la page pour les dernières nouvelles.", NamedTextColor.DARK_GRAY))
                .build());

        TextComponent.Builder page = Component.text();
        int lines = 0;
        for (NewsEntry n : village.news) {
            int needed = 2 + (int) Math.ceil(n.text().length() / (double) CHARS_PER_LINE);
            if (lines > 0 && lines + needed > LINES_PER_PAGE) {
                pages.add(page.build());
                if (pages.size() >= MAX_PAGES) {
                    break;
                }
                page = Component.text();
                lines = 0;
            }
            page.append(Component.text(n.type().symbol + " Jour " + n.day() + " · " + DATE.format(Instant.ofEpochMilli(n.time())) + "\n",
                    n.type().color));
            page.append(Component.text(n.text() + "\n\n", NamedTextColor.BLACK));
            lines += needed;
        }
        if (lines > 0 && pages.size() < MAX_PAGES) {
            pages.add(page.build());
        }
        player.openBook(Book.book(Component.text("Gazette"), Component.text("Mairie"), pages));
    }
}
