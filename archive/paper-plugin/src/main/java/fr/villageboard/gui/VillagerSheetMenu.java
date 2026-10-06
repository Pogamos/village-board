package fr.villageboard.gui;

import fr.villageboard.Text;
import fr.villageboard.VillageBoardPlugin;
import fr.villageboard.model.Village;
import fr.villageboard.model.VillagerRecord;
import fr.villageboard.service.Professions;
import fr.villageboard.service.VillagerActions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Fiche d'un villageois : informations + renommer, localiser, verrouiller, réinitialiser. */
public final class VillagerSheetMenu extends Menu {

    private static final String PERMISSION = "villageboard.manage";

    private final Village village;
    private final UUID uuid;
    private final Menu parent;

    public VillagerSheetMenu(VillageBoardPlugin plugin, Village village, UUID uuid, Menu parent) {
        super(plugin, 5, Component.text("Fiche du villageois"));
        this.village = village;
        this.uuid = uuid;
        this.parent = parent;
    }

    @Override
    protected void build(Player viewer) {
        set(36, Items.back(), parent::open);
        set(44, Items.close(), Player::closeInventory);

        VillagerRecord r = plugin.service().record(uuid);
        if (r == null) {
            set(13, Items.of(Material.SKELETON_SKULL, Items.title("Ce villageois n'est plus au registre", NamedTextColor.GRAY),
                    List.of(Items.line("Consulte les actualités pour en savoir plus."))));
            return;
        }
        Villager villager = VillagerActions.live(uuid);
        if (villager != null) {
            plugin.service().refresh(r, villager);
        }
        boolean manage = viewer.hasPermission(PERMISSION);

        set(4, info(r, villager, viewer));

        action(19, Material.NAME_TAG, "Renommer",
                List.of("Change le nom affiché au-dessus", "de sa tête (comme un nametag)."),
                manage, villager != null, null, p -> {
                    p.closeInventory();
                    plugin.prompts().ask(p, "Nouveau nom pour " + r.displayName()
                            + " ? (« - » pour retirer le nom, « annuler » pour annuler)", input -> {
                        Villager target = VillagerActions.live(uuid);
                        if (target == null) {
                            Text.error(p, "Le villageois n'est plus à portée.");
                            return;
                        }
                        plugin.actions().rename(p, target, input);
                        open(p);
                    });
                });

        action(21, Material.SPYGLASS, "Localiser",
                List.of("Contour lumineux pendant " + plugin.settings().glowSeconds() + " s", "et boussole dans la barre d'action."),
                true, true, null, p -> {
                    p.closeInventory();
                    plugin.actions().locate(p, r);
                });

        String lockTitle = r.locked ? "Déverrouiller le métier" : "Verrouiller le métier";
        List<String> lockLore = r.locked
                ? List.of("Le métier est figé.", "Clic pour le libérer à nouveau.")
                : List.of("Empêche de prendre ou de perdre", "un métier, même sans poste de travail.");
        action(23, Material.TRIPWIRE_HOOK, lockTitle, lockLore,
                manage, villager != null, r.baby ? "Un enfant n'a pas de métier." : null, p -> {
                    Villager target = VillagerActions.live(uuid);
                    if (target != null) {
                        plugin.actions().toggleLock(p, target);
                    }
                    render(p);
                });

        action(25, Material.BRUSH, "Réinitialiser le métier",
                List.of("Le villageois quitte son poste,", "perd son expérience et ses échanges."),
                manage, villager != null, r.employed() ? null : "Aucun métier à réinitialiser.", p ->
                        new ConfirmMenu(plugin, "Réinitialiser " + r.displayName() + " ?",
                                List.of(Professions.describe(r) + " → sans emploi",
                                        "Expérience et échanges perdus.",
                                        "Le verrou éventuel est retiré."),
                                yes -> {
                                    Villager target = VillagerActions.live(uuid);
                                    if (target == null) {
                                        Text.error(yes, "Le villageois n'est plus à portée.");
                                    } else {
                                        plugin.actions().resetJob(yes, target);
                                    }
                                    // Laisse le temps au villageois de changer de métier avant d'afficher la fiche.
                                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> open(yes), 5L);
                                },
                                this::open).open(p));

        if (villager == null && manage) {
            set(40, Items.of(Material.LAVA_BUCKET, Items.title("Retirer du registre", NamedTextColor.RED), List.of(
                            Items.line("Si ce villageois a disparu sans"),
                            Items.line("laisser de trace (chunk supprimé…)."))),
                    p -> new ConfirmMenu(plugin, "Retirer " + r.displayName() + " ?",
                            List.of("Il réapparaîtra au registre s'il est revu."),
                            yes -> {
                                plugin.service().forget(uuid);
                                parent.open(yes);
                            },
                            this::open).open(p));
        }
    }

    /** Bouton d'action, grisé avec la raison si l'action est impossible. */
    private void action(int slot, Material icon, String title, List<String> description, boolean permitted,
                        boolean loaded, String unavailable, Consumer<Player> onClick) {
        String reason = !permitted ? "Tu n'as pas la permission."
                : !loaded ? "Le villageois doit être dans une zone chargée."
                : unavailable;
        List<Component> lore = new ArrayList<>(description.stream().map(Items::line).toList());
        lore.add(Component.empty());
        if (reason != null) {
            lore.add(Items.line(reason, NamedTextColor.RED));
            set(slot, Items.of(Material.GRAY_DYE, Items.title(title, NamedTextColor.DARK_GRAY), lore));
        } else {
            lore.add(Items.hint("Clic"));
            set(slot, Items.of(icon, Items.title(title), lore), onClick);
        }
    }

    private ItemStack info(VillagerRecord r, Villager villager, Player viewer) {
        List<Component> lore = new ArrayList<>();
        if (r.baby) {
            lore.add(Items.line("Enfant", NamedTextColor.AQUA));
        } else {
            lore.add(Items.line("Métier : " + Professions.info(r.profession).label(), NamedTextColor.AQUA));
            if (r.employed()) {
                String xp = villager != null ? " · " + villager.getVillagerExperience() + " xp" : "";
                lore.add(Items.line("Niveau : " + Professions.levelName(r.level) + " (" + r.level + "/5)" + xp));
            }
        }
        if (r.locked) {
            lore.add(Items.line("■ Métier verrouillé", NamedTextColor.GOLD));
        }
        lore.add(Component.empty());

        if (villager != null) {
            AttributeInstance maxHealth = villager.getAttribute(Attribute.MAX_HEALTH);
            double max = maxHealth != null ? maxHealth.getValue() : 20;
            lore.add(Items.line("Santé : " + Math.round(villager.getHealth()) + " / " + Math.round(max) + " ❤"));
            if (!r.baby) {
                lore.add(Items.line("Échanges proposés : " + villager.getRecipeCount()));
            }
        }
        Location loc = villager != null ? villager.getLocation() : r.location();
        if (loc != null) {
            String where = "Position : " + Text.coords(loc);
            if (loc.getWorld().equals(viewer.getWorld())) {
                where += " (" + (int) loc.distance(viewer.getLocation()) + " blocs, " + Text.direction(viewer.getLocation(), loc) + ")";
            } else {
                where += " · " + loc.getWorld().getName();
            }
            lore.add(Items.line(where));
        }
        if (villager != null) {
            Location job = villager.getMemory(MemoryKey.JOB_SITE);
            if (job != null) {
                lore.add(Items.line("Poste de travail : " + Text.coords(job)));
            }
            Location home = villager.getMemory(MemoryKey.HOME);
            lore.add(Items.line(home != null ? "Lit : " + Text.coords(home) : "Lit : aucun", NamedTextColor.GRAY));
        }
        lore.add(Component.empty());

        if (r.born) {
            lore.add(Items.line("Né au village le jour " + r.firstSeenDay, NamedTextColor.DARK_GREEN));
            if (r.parents != null) {
                lore.add(Items.line("Parents : " + r.parents, NamedTextColor.DARK_GREEN));
            }
        } else {
            lore.add(Items.line("Recensé depuis le jour " + r.firstSeenDay, NamedTextColor.DARK_GRAY));
        }
        lore.add(villager != null
                ? Items.line("● Présent (zone chargée)", NamedTextColor.GREEN)
                : Items.line("○ Hors de portée · vu " + Text.ago(r.lastSeen), NamedTextColor.DARK_GRAY));

        ItemStack item = Items.of(Professions.icon(r), Items.title(r.displayName(), NamedTextColor.YELLOW), lore);
        return r.locked ? Items.glint(item) : item;
    }
}
