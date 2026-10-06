package fr.villageboard.listener;

import fr.villageboard.Text;
import fr.villageboard.VillageBoardPlugin;
import fr.villageboard.gui.MainMenu;
import fr.villageboard.model.Village;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Clic droit sur le tableau de la mairie → ouvre le menu ; le tableau est protégé. */
public final class BoardListener implements Listener {

    private final VillageBoardPlugin plugin;

    public BoardListener(VillageBoardPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND || block == null) {
            return;
        }
        Village village = plugin.service().boardAt(block);
        if (village == null) {
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        Player player = event.getPlayer();
        if (!player.hasPermission("villageboard.use")) {
            Text.error(player, "Tu n'as pas accès au tableau de la mairie.");
            return;
        }
        new MainMenu(plugin, village).open(player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Village village = plugin.service().boardAt(event.getBlock());
        if (village != null) {
            event.setCancelled(true);
            Text.error(event.getPlayer(), "C'est le tableau de la mairie de " + village.name
                    + ". Pour le retirer : /vb remove " + village.id);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> plugin.service().boardAt(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> plugin.service().boardAt(b) != null);
    }
}
