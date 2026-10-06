package fr.villageboard.gui;

import fr.villageboard.VillageBoardPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/** Bloque toute manipulation d'objets dans les menus et route les clics vers leurs actions. */
public final class MenuListener implements Listener {

    private final VillageBoardPlugin plugin;

    public MenuListener(VillageBoardPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof Menu menu)) {
            return;
        }
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= top.getSize() || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // Ouvrir un autre inventaire pendant l'événement de clic est déconseillé : on attend un tick.
        plugin.getServer().getScheduler().runTask(plugin, () -> menu.click(player, slot));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Menu) {
            event.setCancelled(true);
        }
    }
}
