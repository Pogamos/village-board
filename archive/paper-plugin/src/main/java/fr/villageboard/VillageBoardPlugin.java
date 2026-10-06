package fr.villageboard;

import fr.villageboard.command.VillageBoardCommand;
import fr.villageboard.gui.MenuListener;
import fr.villageboard.listener.BoardListener;
import fr.villageboard.listener.VillagerListener;
import fr.villageboard.service.ChatPrompts;
import fr.villageboard.service.VillageService;
import fr.villageboard.service.VillagerActions;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class VillageBoardPlugin extends JavaPlugin {

    private static final long AUTOSAVE_TICKS = 60 * 20L;

    private Settings settings;
    private VillageService service;
    private VillagerActions actions;
    private ChatPrompts prompts;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.from(getConfig());
        service = new VillageService(this, settings);
        service.load();
        actions = new VillagerActions(this, service, settings);
        prompts = new ChatPrompts(this);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new VillagerListener(service), this);
        pm.registerEvents(new BoardListener(this), this);
        pm.registerEvents(new MenuListener(this), this);
        pm.registerEvents(prompts, this);

        VillageBoardCommand command = new VillageBoardCommand(this);
        PluginCommand pluginCommand = getCommand("villageboard");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }

        getServer().getScheduler().runTaskTimer(this, service::scan, 40L, settings.scanIntervalSeconds() * 20L);
        getServer().getScheduler().runTaskTimer(this, () -> service.saveAll(true), AUTOSAVE_TICKS, AUTOSAVE_TICKS);
        getLogger().info(service.villages().size() + " village(s) chargé(s).");
    }

    @Override
    public void onDisable() {
        if (actions != null) {
            actions.cleanup();
        }
        if (service != null) {
            service.saveAll(false);
        }
    }

    public Settings settings() {
        return settings;
    }

    public VillageService service() {
        return service;
    }

    public VillagerActions actions() {
        return actions;
    }

    public ChatPrompts prompts() {
        return prompts;
    }
}
