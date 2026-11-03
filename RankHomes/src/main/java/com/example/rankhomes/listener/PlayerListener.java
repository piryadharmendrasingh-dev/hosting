package com.example.rankhomes.listener;

import com.example.rankhomes.RankHomes;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Data lifecycle and teleport-cancellation triggers. */
public final class PlayerListener implements Listener {

    private final RankHomes plugin;

    public PlayerListener(RankHomes plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.homes().preload(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.teleports().forget(event.getPlayer().getUniqueId());
        plugin.homes().unload(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock() || !plugin.settings().cancelOnMove()) {
            return;
        }
        Player player = event.getPlayer();
        if (plugin.teleports().isPending(player.getUniqueId())) {
            plugin.teleports().cancel(player, "teleport-cancelled-move");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !plugin.settings().cancelOnDamage()) {
            return;
        }
        if (event.getFinalDamage() > 0 && plugin.teleports().isPending(player.getUniqueId())) {
            plugin.teleports().cancel(player, "teleport-cancelled-damage");
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        plugin.teleports().cancel(event.getEntity(), null);
    }
}
