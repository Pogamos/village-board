package fr.villageboard.gui;

import fr.villageboard.VillageBoardPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Menu en coffre : chaque case peut porter une action au clic. */
public abstract class Menu implements InventoryHolder {

    protected final VillageBoardPlugin plugin;
    private final Inventory inventory;
    private final Map<Integer, Consumer<Player>> actions = new HashMap<>();

    protected Menu(VillageBoardPlugin plugin, int rows, Component title) {
        this.plugin = plugin;
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    protected abstract void build(Player viewer);

    protected final void set(int slot, ItemStack item) {
        inventory.setItem(slot, item);
    }

    protected final void set(int slot, ItemStack item, Consumer<Player> action) {
        inventory.setItem(slot, item);
        actions.put(slot, action);
    }

    public final void open(Player player) {
        render(player);
        player.openInventory(inventory);
    }

    /** Reconstruit le contenu sans rouvrir l'inventaire. */
    public final void render(Player player) {
        inventory.clear();
        actions.clear();
        build(player);
        ItemStack filler = Items.filler();
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, filler);
            }
        }
    }

    final void click(Player player, int slot) {
        Consumer<Player> action = actions.get(slot);
        if (action != null) {
            player.playSound(player, Sound.UI_BUTTON_CLICK, 0.4f, 1f);
            action.accept(player);
        }
    }
}
