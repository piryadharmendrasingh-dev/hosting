package com.example.rankhomes.service;

import com.example.rankhomes.RankHomes;
import com.example.rankhomes.config.Messages;
import com.example.rankhomes.config.Settings;
import com.example.rankhomes.storage.Home;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Delayed, cancellable, safety-checked teleports to homes. Main thread only. */
public final class TeleportService {

    private static final class Pending {
        final Home home;
        int remaining;
        BukkitTask task;

        Pending(Home home, int remaining) {
            this.home = home;
            this.remaining = remaining;
        }
    }

    private final RankHomes plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    public TeleportService(RankHomes plugin) {
        this.plugin = plugin;
    }

    public boolean isPending(UUID id) {
        return pending.containsKey(id);
    }

    /** Begins a teleport (with delay/cooldown rules applied). */
    public void start(Player player, Home home) {
        Settings s = plugin.settings();
        Messages m = plugin.messages();
        UUID id = player.getUniqueId();

        if (pending.containsKey(id)) {
            m.send(player, "teleport-already-pending");
            return;
        }
        if (home.resolveWorld() == null) {
            m.send(player, "world-missing", "%home%", home.name(), "%world%", home.worldName());
            return;
        }
        if (s.teleportCooldown() > 0 && !player.hasPermission("rankhomes.bypass.cooldown")) {
            Long last = cooldowns.get(id);
            if (last != null) {
                long left = (last + s.teleportCooldown() * 1000L - System.currentTimeMillis() + 999L) / 1000L;
                if (left > 0) {
                    m.send(player, "teleport-cooldown", "%seconds%", left);
                    return;
                }
            }
        }

        int delay = player.hasPermission("rankhomes.bypass.delay") ? 0 : s.teleportDelay();
        if (delay <= 0) {
            execute(player, home);
            return;
        }

        Pending p = new Pending(home, delay);
        pending.put(id, p);
        m.send(player, "teleport-start", "%home%", home.name(), "%seconds%", delay);
        p.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tick(player, p), 20L, 20L);
    }

    private void tick(Player player, Pending p) {
        UUID id = player.getUniqueId();
        if (!player.isOnline() || pending.get(id) != p) {
            stop(id);
            return;
        }
        p.remaining--;
        if (p.remaining <= 0) {
            stop(id);
            execute(player, p.home);
        } else {
            showCountdown(player, p);
        }
    }

    private void showCountdown(Player player, Pending p) {
        Component line = plugin.messages().component("teleport-countdown", "%seconds%", p.remaining, "%home%", p.home.name());
        switch (plugin.settings().countdownDisplay()) {
            case CHAT -> player.sendMessage(line);
            case ACTION_BAR -> player.sendActionBar(line);
            case TITLE -> player.showTitle(Title.title(Component.empty(), line,
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO)));
            case NONE -> { }
        }
    }

    /** Cancels a pending teleport; {@code messageKey} may be null for a silent cancel. */
    public void cancel(Player player, String messageKey) {
        if (stop(player.getUniqueId()) && messageKey != null) {
            plugin.messages().send(player, messageKey);
        }
    }

    private boolean stop(UUID id) {
        Pending p = pending.remove(id);
        if (p == null) {
            return false;
        }
        if (p.task != null) {
            p.task.cancel();
        }
        return true;
    }

    public void forget(UUID id) {
        stop(id);
        cooldowns.remove(id);
    }

    public void shutdown() {
        for (Pending p : pending.values()) {
            if (p.task != null) {
                p.task.cancel();
            }
        }
        pending.clear();
    }

    // ------------------------------------------------------------ execution

    private void execute(Player player, Home home) {
        World world = home.resolveWorld();
        if (world == null) {
            plugin.messages().send(player, "world-missing", "%home%", home.name(), "%world%", home.worldName());
            return;
        }
        int cx = ((int) Math.floor(home.x())) >> 4;
        int cz = ((int) Math.floor(home.z())) >> 4;
        // Load the chunk off the main thread, then finish on the main thread.
        world.getChunkAtAsync(cx, cz).whenComplete((chunk, error) -> plugin.runSync(() -> {
            if (error != null) {
                plugin.getLogger().warning("Could not load chunk for home '" + home.name() + "': " + error.getMessage());
                plugin.messages().send(player, "teleport-failed");
            } else {
                finish(player, home);
            }
        }));
    }

    private void finish(Player player, Home home) {
        if (!player.isOnline()) {
            return;
        }
        Messages m = plugin.messages();
        Settings s = plugin.settings();
        World world = home.resolveWorld(); // the world may have been unloaded while we waited
        if (world == null) {
            m.send(player, "world-missing", "%home%", home.name(), "%world%", home.worldName());
            return;
        }

        Location target = home.toLocation(world);
        boolean adjusted = false;
        if (s.safetyEnabled() && !player.hasPermission("rankhomes.bypass.safety")) {
            SafetyChecker checker = new SafetyChecker(s);
            if (!checker.isSafe(target)) {
                Optional<Location> alternative = s.unsafeMode() == Settings.UnsafeMode.SEARCH
                        ? checker.findNearby(target) : Optional.empty();
                if (alternative.isEmpty()) {
                    m.send(player, "teleport-unsafe", "%home%", home.name());
                    return;
                }
                target = alternative.get();
                adjusted = true;
            }
        }

        boolean wasAdjusted = adjusted;
        UUID id = player.getUniqueId();
        player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((success, error) ->
                plugin.runSync(() -> {
                    if (error != null || !Boolean.TRUE.equals(success)) {
                        m.send(player, "teleport-failed");
                        return;
                    }
                    cooldowns.put(id, System.currentTimeMillis());
                    m.send(player, "teleported", "%home%", home.name());
                    if (wasAdjusted) {
                        m.send(player, "teleport-adjusted");
                    }
                }));
    }
}
