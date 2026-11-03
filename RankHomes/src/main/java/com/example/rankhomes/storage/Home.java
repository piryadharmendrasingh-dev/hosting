package com.example.rankhomes.storage;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/**
 * A saved home. The world is remembered by UID and name so a renamed or
 * re-created world can still be resolved; {@link #resolveWorld()} returns
 * null when neither matches a currently loaded world.
 */
public record Home(String name, String worldName, String worldUid,
                   double x, double y, double z, float yaw, float pitch, long createdAt) {

    public static Home at(String name, Location loc) {
        World world = loc.getWorld();
        return new Home(name, world.getName(), world.getUID().toString(),
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch(), System.currentTimeMillis());
    }

    public boolean hasValidCoordinates() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Float.isFinite(yaw) && Float.isFinite(pitch);
    }

    /** The loaded world this home belongs to, or null if it is deleted / not loaded. */
    public World resolveWorld() {
        World world = null;
        if (worldUid != null && !worldUid.isEmpty()) {
            try {
                world = Bukkit.getWorld(UUID.fromString(worldUid));
            } catch (IllegalArgumentException ignored) {
                // malformed UID, fall back to the name
            }
        }
        if (world == null && worldName != null) {
            world = Bukkit.getWorld(worldName);
        }
        return world;
    }

    public Location toLocation(World world) {
        return new Location(world, x, y, z, yaw, pitch);
    }
}
