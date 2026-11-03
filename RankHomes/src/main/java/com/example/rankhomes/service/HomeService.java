package com.example.rankhomes.service;

import com.example.rankhomes.RankHomes;
import com.example.rankhomes.storage.Home;
import com.example.rankhomes.storage.HomeStorage;
import com.example.rankhomes.storage.PlayerData;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.logging.Level;

/** Home bookkeeping for online players: cache in memory, persist asynchronously. */
public final class HomeService {

    public enum SetResult { CREATED, UPDATED, LIMIT_REACHED, BLOCKED_WORLD }

    private final RankHomes plugin;
    private final HomeStorage storage;
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();

    public HomeService(RankHomes plugin, HomeStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    // ------------------------------------------------------------- loading

    /** Starts loading a player's data in the background so commands are instant. */
    public void preload(Player player) {
        UUID id = player.getUniqueId();
        if (cache.containsKey(id)) {
            return;
        }
        storage.loadPlayer(id).whenComplete((data, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Could not preload homes of " + player.getName(), error);
                return;
            }
            plugin.runSync(() -> {
                if (player.isOnline()) {
                    cache.putIfAbsent(id, data);
                }
            });
        });
    }

    public void unload(UUID id) {
        cache.remove(id);
    }

    /**
     * Cached data of an online player; falls back to a short blocking load if the
     * background preload has not finished yet.
     */
    public PlayerData data(UUID id) {
        PlayerData cached = cache.get(id);
        if (cached != null) {
            return cached;
        }
        try {
            PlayerData loaded = storage.loadPlayer(id).get(10, TimeUnit.SECONDS);
            PlayerData existing = cache.putIfAbsent(id, loaded);
            return existing != null ? existing : loaded;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new DataUnavailableException("Interrupted while loading homes", ex);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not load homes of " + id, ex);
            throw new DataUnavailableException("Could not load homes of " + id, ex);
        }
    }

    // ------------------------------------------------------------- queries

    public List<Home> getHomes(UUID id) {
        return data(id).homes().values().stream()
                .sorted(Comparator.comparingLong(Home::createdAt).thenComparing(Home::name))
                .toList();
    }

    public Optional<Home> getHome(UUID id, String name) {
        return Optional.ofNullable(data(id).homes().get(name.toLowerCase(Locale.ROOT)));
    }

    public LimitService.LimitResult getLimit(Player player) {
        return plugin.limits().resolve(player, data(player.getUniqueId()).limitOverride());
    }

    // ------------------------------------------------------------ mutation

    public SetResult setHome(Player player, String name) {
        Location loc = player.getLocation();
        World world = loc.getWorld();
        if (plugin.settings().blockedWorlds().contains(world.getName().toLowerCase(Locale.ROOT))
                && !player.hasPermission("rankhomes.bypass.blockedworlds")) {
            return SetResult.BLOCKED_WORLD;
        }
        PlayerData data = data(player.getUniqueId());
        boolean exists = data.homes().containsKey(name);
        if (!exists && data.homes().size() >= getLimit(player).limit()) {
            return SetResult.LIMIT_REACHED;
        }
        Home home = Home.at(name, loc);
        if (exists) {
            // keep the original creation time so list order stays stable
            home = new Home(home.name(), home.worldName(), home.worldUid(), home.x(), home.y(), home.z(),
                    home.yaw(), home.pitch(), data.homes().get(name).createdAt());
        }
        data.homes().put(name, home);
        storage.saveHome(player.getUniqueId(), home);
        return exists ? SetResult.UPDATED : SetResult.CREATED;
    }

    public boolean deleteHome(UUID id, String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Home removed = data(id).homes().remove(key);
        if (removed == null) {
            return false;
        }
        storage.deleteHome(id, key);
        return true;
    }

    /** Sets (or with null clears) an admin override. Works for offline players too. */
    public void setLimitOverride(UUID id, Integer limit) {
        PlayerData cached = cache.get(id);
        if (cached != null) {
            cached.limitOverride(limit);
        }
        storage.setLimitOverride(id, limit);
    }
}
