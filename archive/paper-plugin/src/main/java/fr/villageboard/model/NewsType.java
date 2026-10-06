package fr.villageboard.model;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

public enum NewsType {
    BIRTH("✦", NamedTextColor.DARK_GREEN),
    DEATH("✝", NamedTextColor.DARK_RED),
    JOB("⚒", NamedTextColor.DARK_AQUA),
    JOB_LOST("✖", NamedTextColor.GOLD),
    ARRIVAL("→", NamedTextColor.DARK_BLUE),
    DEPARTURE("←", NamedTextColor.DARK_PURPLE),
    INFO("✎", NamedTextColor.DARK_GRAY);

    public final String symbol;
    public final TextColor color;

    NewsType(String symbol, TextColor color) {
        this.symbol = symbol;
        this.color = color;
    }
}
