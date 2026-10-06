package fr.villageboard.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

import java.util.List;

final class Items {

    private Items() {
    }

    static ItemStack of(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream().map(c -> c.decoration(TextDecoration.ITALIC, false)).toList());
            meta.addItemFlags(ItemFlag.values());
        });
        return item;
    }

    static ItemStack glint(ItemStack item) {
        item.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        return item;
    }

    static ItemStack filler() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        item.editMeta(meta -> meta.setHideTooltip(true));
        return item;
    }

    static Component title(String text) {
        return Component.text(text, NamedTextColor.GOLD, TextDecoration.BOLD);
    }

    static Component title(String text, TextColor color) {
        return Component.text(text, color, TextDecoration.BOLD);
    }

    static Component line(String text) {
        return Component.text(text, NamedTextColor.GRAY);
    }

    static Component line(String text, TextColor color) {
        return Component.text(text, color);
    }

    static Component hint(String text) {
        return Component.text("▸ " + text, NamedTextColor.YELLOW);
    }

    static ItemStack back() {
        return of(Material.ARROW, title("Retour", NamedTextColor.WHITE), List.of());
    }

    static ItemStack close() {
        return of(Material.BARRIER, title("Fermer", NamedTextColor.RED), List.of());
    }
}
