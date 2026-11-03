package com.example.rankhomes;

import com.example.rankhomes.command.HomeCommand;
import com.example.rankhomes.config.Messages;
import com.example.rankhomes.config.Settings;
import com.example.rankhomes.gui.GuiListener;
import com.example.rankhomes.gui.HomesGui;
import com.example.rankhomes.listener.PlayerListener;
import com.example.rankhomes.service.HomeService;
import com.example.rankhomes.service.LimitService;
import com.example.rankhomes.service.TeleportService;
import com.example.rankhomes.storage.HomeStorage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public final class RankHomes extends JavaPlugin {

    private Settings settings;
    private Messages messages;
    private HomeStorage storage;
    private LimitService limits;
    private HomeService homes;
    private TeleportService teleports;
    private HomesGui gui;
    private final List<Command> registeredCommands = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.load(getConfig(), getLogger());
        messages = new Messages(this);

        storage = new HomeStorage(this);
        try {
            storage.open();
        } catch (SQLException ex) {
            getLogger().log(Level.SEVERE, "Could not open the SQLite database; disabling RankHomes.", ex);
            storage.close();
            storage = null;
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        limits = new LimitService(this);
        homes = new HomeService(this, storage);
        teleports = new TeleportService(this);
        gui = new HomesGui(this);

        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new GuiListener(this), this);
        registerCommands();

        // Support /reload-style enables with players already online.
        for (Player online : getServer().getOnlinePlayers()) {
            homes.preload(online);
        }
        getLogger().info("RankHomes enabled.");
    }

    @Override
    public void onDisable() {
        if (teleports != null) {
            teleports.shutdown();
        }
        unregisterCommands();
        if (storage != null) {
            storage.close(); // flushes queued writes
        }
    }

    /** Re-reads config.yml and re-registers commands (picks up alias changes). */
    public void reloadAll() {
        reloadConfig();
        settings = Settings.load(getConfig(), getLogger());
        limits.refreshHook();
        registerCommands();
    }

    private void registerCommands() {
        unregisterCommands();
        CommandMap map = getServer().getCommandMap();
        for (HomeCommand.Type type : HomeCommand.Type.values()) {
            HomeCommand command = new HomeCommand(this, type, settings.aliases().get(type.label));
            map.register(getName().toLowerCase(), command);
            registeredCommands.add(command);
        }
        getServer().getOnlinePlayers().forEach(Player::updateCommands);
    }

    private void unregisterCommands() {
        if (registeredCommands.isEmpty()) {
            return;
        }
        getServer().getCommandMap().getKnownCommands().values().removeIf(registeredCommands::contains);
        registeredCommands.clear();
    }

    /** Runs on the main thread: immediately if already there, otherwise next tick. */
    public void runSync(Runnable task) {
        if (getServer().isPrimaryThread()) {
            task.run();
        } else if (isEnabled()) {
            getServer().getScheduler().runTask(this, task);
        }
    }

    public Settings settings() { return settings; }
    public Messages messages() { return messages; }
    public LimitService limits() { return limits; }
    public HomeService homes() { return homes; }
    public TeleportService teleports() { return teleports; }
    public HomesGui gui() { return gui; }
}
