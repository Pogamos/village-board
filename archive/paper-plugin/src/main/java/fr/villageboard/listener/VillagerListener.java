package fr.villageboard.listener;

import fr.villageboard.service.Professions;
import fr.villageboard.service.VillageService;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.ZombieVillager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.VillagerCareerChangeEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Alimente le registre et les actualités à partir des événements du jeu. */
public final class VillagerListener implements Listener {

    private static final Map<String, String> KILLERS = Map.ofEntries(
            Map.entry("zombie", "attaque de zombie"),
            Map.entry("zombie_villager", "attaque de zombie-villageois"),
            Map.entry("husk", "attaque de zombie momifié"),
            Map.entry("drowned", "attaque de noyé"),
            Map.entry("pillager", "attaque de pillard"),
            Map.entry("vindicator", "attaque de vindicateur"),
            Map.entry("evoker", "attaque d'évocateur"),
            Map.entry("vex", "attaque de vex"),
            Map.entry("ravager", "attaque de ravageur"),
            Map.entry("witch", "attaque de sorcière"),
            Map.entry("skeleton", "attaque de squelette"),
            Map.entry("stray", "attaque de vagabond"),
            Map.entry("creeper", "explosion de creeper"),
            Map.entry("spider", "attaque d'araignée"),
            Map.entry("cave_spider", "attaque d'araignée venimeuse"),
            Map.entry("iron_golem", "golem de fer"),
            Map.entry("wolf", "attaque de loup"),
            Map.entry("lightning_bolt", "foudre"));

    private static final Map<String, String> DAMAGE_TYPES = Map.ofEntries(
            Map.entry("fall", "chute"),
            Map.entry("drown", "noyade"),
            Map.entry("lava", "lave"),
            Map.entry("in_fire", "brûlures"),
            Map.entry("on_fire", "brûlures"),
            Map.entry("campfire", "brûlures"),
            Map.entry("hot_floor", "bloc de magma"),
            Map.entry("explosion", "explosion"),
            Map.entry("player_explosion", "explosion"),
            Map.entry("lightning_bolt", "foudre"),
            Map.entry("starve", "faim"),
            Map.entry("in_wall", "étouffement"),
            Map.entry("cramming", "écrasement (trop de monde)"),
            Map.entry("cactus", "cactus"),
            Map.entry("sweet_berry_bush", "buisson de baies"),
            Map.entry("freeze", "gel"),
            Map.entry("magic", "magie"),
            Map.entry("indirect_magic", "magie"),
            Map.entry("wither", "wither"),
            Map.entry("falling_anvil", "chute d'enclume"),
            Map.entry("falling_block", "chute de bloc"),
            Map.entry("falling_stalactite", "stalactite"),
            Map.entry("generic_kill", "commande /kill"),
            Map.entry("out_of_world", "chute dans le vide"));

    private final VillageService service;
    /** Parents d'un bébé, entre l'événement de reproduction et l'apparition du bébé. */
    private final Map<UUID, String> pendingParents = new HashMap<>();

    public VillagerListener(VillageService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (event.getEntity() instanceof Villager child
                && event.getMother() instanceof Villager mother
                && event.getFather() instanceof Villager father) {
            if (pendingParents.size() > 64) {
                pendingParents.clear();
            }
            pendingParents.put(child.getUniqueId(), service.displayName(mother) + " et " + service.displayName(father));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getEntity() instanceof Villager child) {
            String parents = pendingParents.remove(child.getUniqueId());
            if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.BREEDING) {
                service.recordBirth(child, parents);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Villager villager) {
            service.recordDeath(villager, cause(event.getDamageSource()));
        }
    }

    /** Verrou : un villageois verrouillé ne prend ni ne perd de métier. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCareerLock(VillagerCareerChangeEvent event) {
        if (service.isLocked(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCareerChange(VillagerCareerChangeEvent event) {
        service.recordCareerChange(event.getEntity(), Professions.key(event.getProfession()), event.getReason());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        if (event.getEntity() instanceof Villager villager) {
            service.recordTransform(villager, event.getTransformedEntity().getType());
        } else if (event.getEntity() instanceof ZombieVillager && event.getTransformedEntity() instanceof Villager cured) {
            service.recordCure(cured);
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Villager villager) {
                service.observe(villager, true);
            }
        }
    }

    /** Mémorise la dernière position des villageois qui sortent des chunks chargés. */
    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Villager villager) {
                service.observe(villager, true);
            }
        }
    }

    private static String cause(DamageSource source) {
        Entity killer = source.getCausingEntity();
        if (killer instanceof Player player) {
            return "tué par " + player.getName();
        }
        if (killer != null) {
            String key = killer.getType().getKey().getKey();
            return KILLERS.getOrDefault(key, "attaque (" + key.replace('_', ' ') + ")");
        }
        String type = source.getDamageType().getKey().getKey();
        return DAMAGE_TYPES.getOrDefault(type, type.replace('_', ' '));
    }
}
