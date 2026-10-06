package fr.villageboard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;

import java.time.Duration;

public final class Text {

    private static final Component PREFIX = Component.text("[Mairie] ", NamedTextColor.GOLD);
    private static final String[] DIRECTIONS =
            {"Nord", "Nord-Est", "Est", "Sud-Est", "Sud", "Sud-Ouest", "Ouest", "Nord-Ouest"};

    private Text() {
    }

    public static void info(CommandSender to, String message) {
        to.sendMessage(PREFIX.append(Component.text(message, NamedTextColor.YELLOW)));
    }

    public static void error(CommandSender to, String message) {
        to.sendMessage(PREFIX.append(Component.text(message, NamedTextColor.RED)));
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    public static String coords(Location loc) {
        return loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
    }

    /** Direction cardinale pour aller de {@code from} vers {@code to} (Nord = -Z). */
    public static String direction(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        if (angle < 0) {
            angle += 360;
        }
        return DIRECTIONS[(int) Math.round(angle / 45) % 8];
    }

    public static String ago(long epochMillis) {
        Duration d = Duration.ofMillis(Math.max(0, System.currentTimeMillis() - epochMillis));
        if (d.toMinutes() < 1) {
            return "à l'instant";
        }
        if (d.toHours() < 1) {
            return "il y a " + d.toMinutes() + " min";
        }
        if (d.toDays() < 1) {
            return "il y a " + d.toHours() + " h";
        }
        return "il y a " + d.toDays() + " j";
    }

    public static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
