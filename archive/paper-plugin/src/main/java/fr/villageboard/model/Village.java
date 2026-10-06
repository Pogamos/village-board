package fr.villageboard.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** Un village = un tableau de mairie + un rayon autour de lui. */
public final class Village {

    public final String id;
    public String name;
    public String world;
    public int x;
    public int y;
    public int z;
    public int radius;
    public final Map<UUID, VillagerRecord> villagers = new HashMap<>();
    /** Actualités, la plus récente en premier. */
    public final Deque<NewsEntry> news = new ArrayDeque<>();
    public boolean dirty;

    public Village(String id) {
        this.id = id;
    }

    public boolean isBoard(Block block) {
        return block.getX() == x && block.getY() == y && block.getZ() == z
                && block.getWorld().getName().equals(world);
    }

    /** Distance horizontale au carré jusqu'au tableau, ou +∞ si autre monde. */
    public double distanceSq(Location loc) {
        if (loc.getWorld() == null || !loc.getWorld().getName().equals(world)) {
            return Double.POSITIVE_INFINITY;
        }
        double dx = loc.getX() - (x + 0.5);
        double dz = loc.getZ() - (z + 0.5);
        return dx * dx + dz * dz;
    }

    public boolean contains(Location loc) {
        return distanceSq(loc) <= (double) radius * radius;
    }

    public World bukkitWorld() {
        return Bukkit.getWorld(world);
    }

    public long currentDay() {
        World w = bukkitWorld();
        return w == null ? 0 : w.getFullTime() / 24000 + 1;
    }

    public long count(Predicate<VillagerRecord> filter) {
        return villagers.values().stream().filter(filter).count();
    }
}
