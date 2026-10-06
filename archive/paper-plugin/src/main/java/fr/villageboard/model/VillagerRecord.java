package fr.villageboard.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/**
 * Fiche d'un villageois au registre de la mairie. Conservée même quand le villageois
 * est dans un chunk non chargé, avec sa dernière position connue.
 */
public final class VillagerRecord {

    public final UUID uuid;
    public String generatedName;
    public String customName;
    public String profession = "none";
    public int level = 1;
    public boolean baby;
    public boolean locked;
    public String world;
    public double x;
    public double y;
    public double z;
    public long firstSeen;
    public long firstSeenDay;
    public boolean born;
    public String parents;
    public long lastSeen;

    public VillagerRecord(UUID uuid) {
        this.uuid = uuid;
    }

    public String displayName() {
        return customName != null ? customName : generatedName;
    }

    public boolean employed() {
        return !baby && !profession.equals("none") && !profession.equals("nitwit");
    }

    /** Dernière position connue, ou null si le monde n'est plus chargé. */
    public Location location() {
        World w = world == null ? null : Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z);
    }
}
