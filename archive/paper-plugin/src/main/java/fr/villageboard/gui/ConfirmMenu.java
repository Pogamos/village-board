package fr.villageboard.gui;

import fr.villageboard.VillageBoardPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Consumer;

public final class ConfirmMenu extends Menu {

    private final String question;
    private final List<String> details;
    private final Consumer<Player> onConfirm;
    private final Consumer<Player> onCancel;

    public ConfirmMenu(VillageBoardPlugin plugin, String question, List<String> details,
                       Consumer<Player> onConfirm, Consumer<Player> onCancel) {
        super(plugin, 3, Component.text("Confirmation"));
        this.question = question;
        this.details = details;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
    }

    @Override
    protected void build(Player viewer) {
        set(11, Items.of(Material.LIME_WOOL, Items.title("Confirmer", NamedTextColor.GREEN), List.of()), onConfirm);
        set(13, Items.of(Material.PAPER, Items.title(question, NamedTextColor.WHITE),
                details.stream().map(Items::line).toList()));
        set(15, Items.of(Material.RED_WOOL, Items.title("Annuler", NamedTextColor.RED), List.of()), onCancel);
    }
}
