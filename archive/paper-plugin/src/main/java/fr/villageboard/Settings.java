package fr.villageboard;

import org.bukkit.configuration.file.FileConfiguration;

public record Settings(int defaultRadius, int scanIntervalSeconds, int maxNews, int glowSeconds, boolean autoNames) {

    static Settings from(FileConfiguration config) {
        return new Settings(
                Math.max(8, config.getInt("default-radius", 96)),
                Math.max(1, config.getInt("scan-interval-seconds", 10)),
                Math.max(10, config.getInt("news-max-entries", 200)),
                Math.max(1, config.getInt("locate-glow-seconds", 30)),
                config.getBoolean("auto-names", true));
    }
}
