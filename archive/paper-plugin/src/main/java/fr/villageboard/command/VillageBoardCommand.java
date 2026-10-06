package fr.villageboard.command;

import fr.villageboard.Text;
import fr.villageboard.VillageBoardPlugin;
import fr.villageboard.gui.MainMenu;
import fr.villageboard.model.Village;
import fr.villageboard.service.VillageService;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * /vb                     ouvre le tableau du village où l'on se trouve
 * /vb open &lt;id&gt;           ouvre le tableau d'un village
 * /vb list                liste les villages
 * /vb create &lt;nom&gt;        fait du bloc visé le tableau d'un nouveau village (admin)
 * /vb radius &lt;id&gt; &lt;r&gt;     change le rayon d'un village (admin)
 * /vb remove &lt;id&gt;         supprime un village et son registre (admin)
 */
public final class VillageBoardCommand implements TabExecutor {

    private static final String USE = "villageboard.use";
    private static final String ADMIN = "villageboard.admin";
    private static final int MIN_RADIUS = 8;
    private static final int MAX_RADIUS = 512;

    private final VillageBoardPlugin plugin;

    public VillageBoardCommand(VillageBoardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> openHere(sender);
            case "open" -> open(sender, args);
            case "list" -> list(sender);
            case "create" -> create(sender, args);
            case "radius" -> radius(sender, args);
            case "remove" -> remove(sender, args);
            default -> help(sender);
        }
        return true;
    }

    private void openHere(CommandSender sender) {
        if (!(sender instanceof Player player) || !check(sender, USE)) {
            return;
        }
        VillageService service = plugin.service();
        Village village = service.villageAt(player.getLocation());
        if (village == null && service.villages().size() == 1) {
            village = service.villages().iterator().next();
        }
        if (village == null) {
            help(sender);
            return;
        }
        new MainMenu(plugin, village).open(player);
    }

    private void open(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || !check(sender, USE)) {
            return;
        }
        Village village = args.length < 2 ? null : plugin.service().village(args[1]);
        if (village == null) {
            Text.error(sender, "Village introuvable. /vb list pour voir les villages.");
            return;
        }
        new MainMenu(plugin, village).open(player);
    }

    private void list(CommandSender sender) {
        if (!check(sender, USE)) {
            return;
        }
        if (plugin.service().villages().isEmpty()) {
            Text.info(sender, "Aucun village. Vise un bloc et tape /vb create <nom>.");
            return;
        }
        for (Village v : plugin.service().villages()) {
            Text.info(sender, v.name + " [" + v.id + "] · " + v.villagers.size() + " habitants · tableau en "
                    + v.x + ", " + v.y + ", " + v.z + " (" + v.world + ") · rayon " + v.radius);
        }
    }

    private void create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || !check(sender, ADMIN)) {
            return;
        }
        if (args.length < 2) {
            Text.error(sender, "Usage : /vb create <nom du village>  (en visant le bloc du tableau)");
            return;
        }
        Block target = player.getTargetBlockExact(6);
        if (target == null || target.getType().isAir()) {
            Text.error(sender, "Vise le bloc qui servira de tableau (panneau, bannière, bloc…), à 6 blocs maximum.");
            return;
        }
        Village existing = plugin.service().boardAt(target);
        if (existing != null) {
            Text.error(sender, "Ce bloc est déjà le tableau de " + existing.name + ".");
            return;
        }
        String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Village village = plugin.service().create(name, target, plugin.settings().defaultRadius());
        Text.info(sender, "Mairie de " + village.name + " créée [" + village.id + "] : "
                + village.villagers.size() + " villageois recensés dans un rayon de " + village.radius + " blocs.");
        Text.info(sender, "Clic droit sur le tableau pour l'ouvrir.");
    }

    private void radius(CommandSender sender, String[] args) {
        if (!check(sender, ADMIN)) {
            return;
        }
        Village village = args.length < 3 ? null : plugin.service().village(args[1]);
        if (village == null) {
            Text.error(sender, "Usage : /vb radius <id> <rayon>");
            return;
        }
        try {
            int radius = Integer.parseInt(args[2]);
            if (radius < MIN_RADIUS || radius > MAX_RADIUS) {
                throw new NumberFormatException();
            }
            plugin.service().setRadius(village, radius);
            Text.info(sender, "Rayon de " + village.name + " : " + radius + " blocs.");
        } catch (NumberFormatException e) {
            Text.error(sender, "Le rayon doit être un nombre entre " + MIN_RADIUS + " et " + MAX_RADIUS + ".");
        }
    }

    private void remove(CommandSender sender, String[] args) {
        if (!check(sender, ADMIN)) {
            return;
        }
        Village village = args.length < 2 ? null : plugin.service().village(args[1]);
        if (village == null) {
            Text.error(sender, "Usage : /vb remove <id>");
            return;
        }
        plugin.service().delete(village);
        Text.info(sender, "Village " + village.name + " supprimé (registre et actualités effacés).");
    }

    private void help(CommandSender sender) {
        Text.info(sender, "/vb — ouvre le tableau du village où tu te trouves");
        Text.info(sender, "/vb open <id> · /vb list");
        if (sender.hasPermission(ADMIN)) {
            Text.info(sender, "/vb create <nom> — le bloc visé devient le tableau de la mairie");
            Text.info(sender, "/vb radius <id> <rayon> · /vb remove <id>");
        }
    }

    private static boolean check(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        Text.error(sender, "Tu n'as pas la permission.");
        return false;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = sender.hasPermission(ADMIN)
                    ? List.of("open", "list", "create", "radius", "remove")
                    : List.of("open", "list");
            return filter(subs, args[0]);
        }
        if (args.length == 2 && List.of("open", "radius", "remove").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(plugin.service().villages().stream().map(v -> v.id).toList(), args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.startsWith(p)).toList();
    }
}
