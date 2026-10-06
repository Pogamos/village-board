package fr.villageboard.service;

import fr.villageboard.Settings;
import fr.villageboard.Text;
import fr.villageboard.VillageBoardPlugin;
import fr.villageboard.model.NewsType;
import fr.villageboard.model.Village;
import fr.villageboard.model.VillagerRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Actions du tableau sur un villageois : renommer, localiser, verrouiller, réinitialiser. */
public final class VillagerActions {

    private static final String GLOW_TEAM = "villageboard_glow";
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
    private static final int MAX_NAME_LENGTH = 32;

    private final VillageBoardPlugin plugin;
    private final VillageService service;
    private final Settings settings;
    private final Map<UUID, BukkitTask> trackers = new HashMap<>();
    private final Set<String> glowEntries = new HashSet<>();

    public VillagerActions(VillageBoardPlugin plugin, VillageService service, Settings settings) {
        this.plugin = plugin;
        this.service = service;
        this.settings = settings;
    }

    /** Le villageois s'il est actuellement chargé dans le monde. */
    public static Villager live(UUID uuid) {
        return Bukkit.getEntity(uuid) instanceof Villager v && v.isValid() ? v : null;
    }

    // ---------------------------------------------------------------- renommer

    /** {@code input} = « - » retire le nom personnalisé. */
    public void rename(Player player, Villager villager, String input) {
        String before = service.displayName(villager);
        String clean = input.replaceAll("\\p{Cntrl}", "").trim();
        if (clean.equals("-")) {
            villager.customName(null);
        } else if (clean.isEmpty()) {
            Text.error(player, "Nom vide, renommage annulé.");
            return;
        } else {
            villager.customName(Component.text(Text.truncate(clean, MAX_NAME_LENGTH)));
        }
        VillagerRecord r = service.observe(villager, false);
        String after = service.displayName(villager);
        Text.info(player, before + " s'appelle désormais " + after + ".");
        Village village = service.villageOf(villager.getUniqueId());
        if (r != null && village != null && !before.equals(after)) {
            service.news(village, NewsType.INFO, before + " se fait désormais appeler " + after + ".");
        }
    }

    // ---------------------------------------------------------------- localiser

    public void locate(Player player, VillagerRecord r) {
        Villager villager = live(r.uuid);
        if (villager != null) {
            service.refresh(r, villager);
            glow(villager);
        }
        Location target = villager != null ? villager.getLocation() : r.location();
        if (target == null) {
            Text.error(player, r.displayName() + " se trouve dans un monde non chargé (" + r.world + ").");
            return;
        }
        String where = "(" + Text.coords(target) + ")";
        if (!target.getWorld().equals(player.getWorld())) {
            Text.info(player, r.displayName() + " se trouve dans le monde " + target.getWorld().getName() + " " + where + ".");
        } else {
            int distance = (int) player.getLocation().distance(target);
            Text.info(player, r.displayName() + " est à " + distance + " blocs, direction "
                    + Text.direction(player.getLocation(), target) + " " + where + ".");
        }
        if (villager != null) {
            Text.info(player, "Contour lumineux activé pendant " + settings.glowSeconds() + " s.");
        } else {
            Text.info(player, "Hors de portée : c'est sa dernière position connue (" + Text.ago(r.lastSeen) + ").");
        }
        track(player, r);
    }

    private void glow(Villager villager) {
        int ticks = settings.glowSeconds() * 20;
        villager.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, ticks, 0, false, false, false));

        // Une équipe dédiée colore le contour en doré, sans toucher aux villageois déjà dans une équipe.
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = scoreboard.getTeam(GLOW_TEAM);
        if (team == null) {
            team = scoreboard.registerNewTeam(GLOW_TEAM);
            team.color(NamedTextColor.GOLD);
        }
        String entry = villager.getUniqueId().toString();
        if (scoreboard.getEntryTeam(entry) == null) {
            team.addEntry(entry);
            glowEntries.add(entry);
            Bukkit.getScheduler().runTaskLater(plugin, () -> unglow(entry), ticks);
        }
    }

    private void unglow(String entry) {
        if (glowEntries.remove(entry)) {
            Team team = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(GLOW_TEAM);
            if (team != null) {
                team.removeEntry(entry);
            }
        }
    }

    /** Barre d'action avec une flèche vers le villageois, tant que le contour dure. */
    private void track(Player player, VillagerRecord r) {
        BukkitTask previous = trackers.remove(player.getUniqueId());
        if (previous != null) {
            previous.cancel();
        }
        int[] remaining = {settings.glowSeconds() * 4};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Villager villager = live(r.uuid);
            Location target = villager != null ? villager.getLocation() : r.location();
            if (!player.isOnline() || remaining[0]-- <= 0 || target == null) {
                BukkitTask self = trackers.remove(player.getUniqueId());
                if (self != null) {
                    self.cancel();
                }
                return;
            }
            String name = villager != null ? service.displayName(villager) : r.displayName();
            Component bar;
            if (!target.getWorld().equals(player.getWorld())) {
                bar = Component.text(name + " · autre dimension", NamedTextColor.GOLD);
            } else {
                double distance = player.getLocation().distance(target);
                if (distance < 3) {
                    bar = Component.text(name + " est juste là", NamedTextColor.GREEN);
                } else {
                    bar = Component.text(name + "  " + arrow(player.getLocation(), target) + "  " + (int) distance + " blocs",
                            NamedTextColor.GOLD);
                }
            }
            player.sendActionBar(bar);
        }, 0L, 5L);
        trackers.put(player.getUniqueId(), task);
    }

    /** Flèche relative au regard du joueur (yaw Minecraft : 0 = Sud, 90 = Ouest). */
    private static String arrow(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double relative = ((targetYaw - from.getYaw()) % 360 + 360) % 360;
        return ARROWS[(int) Math.round(relative / 45) % 8];
    }

    // ---------------------------------------------------------------- verrou & réinitialisation

    public void toggleLock(Player player, Villager villager) {
        boolean locked = !service.isLocked(villager);
        service.setLocked(villager, locked);
        String name = service.displayName(villager);
        Text.info(player, locked
                ? "Métier de " + name + " verrouillé : il ne changera plus."
                : "Métier de " + name + " déverrouillé.");
    }

    /**
     * Rend le villageois sans emploi, comme si son poste de travail avait été cassé puis reposé :
     * il perd son expérience et ses échanges, puis cherchera un poste libre (éventuellement le même).
     */
    public void resetJob(Player player, Villager villager) {
        String profession = Professions.key(villager.getProfession());
        String name = service.displayName(villager);
        if (profession.equals("none")) {
            Text.error(player, name + " n'a pas de métier.");
            return;
        }
        if (profession.equals("nitwit")) {
            Text.error(player, name + " est niais : il ne peut pas exercer de métier.");
            return;
        }
        service.setLocked(villager, false);
        Location site = villager.getMemory(MemoryKey.JOB_SITE);
        boolean released = site == null || releaseJobSite(site);
        villager.setMemory(MemoryKey.JOB_SITE, null);
        villager.setVillagerExperience(0);
        villager.setVillagerLevel(1);
        // Le comportement vanilla « ResetProfession » repasse le villageois sans emploi au tick suivant
        // (il ne s'applique qu'à un villageois sans poste, sans expérience et de niveau 1).
        Text.info(player, name + " quitte son poste de " + Professions.info(profession).label().toLowerCase()
                + " et va chercher un nouveau travail.");
        if (!released) {
            Text.error(player, "Son ancien poste de travail est hors de portée : casse-le et repose-le pour le libérer.");
        }
    }

    /**
     * Libère le point d'intérêt du poste de travail en retirant le bloc puis en le reposant
     * à l'identique (contenu compris) deux ticks plus tard.
     */
    private boolean releaseJobSite(Location site) {
        World world = site.getWorld();
        if (world == null || !world.isChunkLoaded(site.getBlockX() >> 4, site.getBlockZ() >> 4)) {
            return false;
        }
        Block block = site.getBlock();
        BlockState snapshot = block.getState();
        if (block.getState() instanceof BlockInventoryHolder holder) {
            // On vide l'inventaire réel pour que rien ne tombe au sol ; l'instantané le restaurera.
            holder.getInventory().clear();
        }
        block.setType(Material.AIR, false);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (block.getType().isAir()) {
                snapshot.update(true, false);
            }
        }, 2L);
        return true;
    }

    public void cleanup() {
        trackers.values().forEach(BukkitTask::cancel);
        trackers.clear();
        Set.copyOf(glowEntries).forEach(this::unglow);
    }
}
