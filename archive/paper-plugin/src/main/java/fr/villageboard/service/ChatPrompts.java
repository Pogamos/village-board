package fr.villageboard.service;

import fr.villageboard.Text;
import fr.villageboard.VillageBoardPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Pose une question au joueur et capture sa prochaine ligne de chat. */
public final class ChatPrompts implements Listener {

    private static final long TIMEOUT_TICKS = 60 * 20L;
    private static final Set<String> CANCEL_WORDS = Set.of("annuler", "cancel");

    private final VillageBoardPlugin plugin;
    private final Map<UUID, Consumer<String>> pending = new ConcurrentHashMap<>();

    public ChatPrompts(VillageBoardPlugin plugin) {
        this.plugin = plugin;
    }

    public void ask(Player player, String question, Consumer<String> answer) {
        UUID id = player.getUniqueId();
        pending.put(id, answer);
        Text.info(player, question);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (pending.remove(id, answer) && player.isOnline()) {
                Text.error(player, "Délai dépassé, action annulée.");
            }
        }, TIMEOUT_TICKS);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Consumer<String> answer = pending.remove(player.getUniqueId());
        if (answer == null) {
            return;
        }
        event.setCancelled(true);
        String text = Text.plain(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (CANCEL_WORDS.contains(text.toLowerCase())) {
                Text.info(player, "Action annulée.");
            } else {
                answer.accept(text);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
