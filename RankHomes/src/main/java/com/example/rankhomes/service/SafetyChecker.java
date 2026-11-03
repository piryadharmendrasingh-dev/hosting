package com.example.rankhomes.service;

import com.example.rankhomes.config.Settings;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;

import java.util.Optional;
import java.util.Set;

/**
 * Decides whether a standing player (0.6 x 1.8 blocks) fits at a location:
 * inside the world and border, in a loaded chunk, not inside solid collision,
 * not in a dangerous block, and standing on something. Uses real collision
 * shapes so slabs, carpets etc. are handled correctly.
 */
public final class SafetyChecker {

    private static final double HEIGHT = 1.8;
    private static final double EPS = 1.0E-3;

    private final Set<Material> unsafe;
    private final int radius;
    private final int vertical;

    public SafetyChecker(Settings settings) {
        this.unsafe = settings.unsafeBlocks();
        this.radius = settings.searchRadius();
        this.vertical = settings.searchVertical();
    }

    public boolean isSafe(Location loc) {
        World world = loc.getWorld();
        if (world == null || !Double.isFinite(loc.getX()) || !Double.isFinite(loc.getY()) || !Double.isFinite(loc.getZ())) {
            return false;
        }
        double y = loc.getY();
        if (y < world.getMinHeight() + 1 || y + HEIGHT >= world.getMaxHeight()) {
            return false;
        }
        if (!world.getWorldBorder().isInside(loc)) {
            return false;
        }
        int bx = loc.getBlockX();
        int bz = loc.getBlockZ();
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
            return false;
        }

        // The body must not overlap any collision box or dangerous block.
        int feetY = (int) Math.floor(y);
        int topY = (int) Math.floor(y + HEIGHT - EPS);
        for (int by = feetY; by <= topY; by++) {
            Block block = world.getBlockAt(bx, by, bz);
            if (unsafe.contains(block.getType()) || obstructs(block, y)) {
                return false;
            }
        }

        // There must be ground (or water) directly underneath.
        Block floor = world.getBlockAt(bx, (int) Math.floor(y - EPS), bz);
        if (unsafe.contains(floor.getType())) {
            return false;
        }
        return floor.getType() == Material.WATER || supports(floor, y);
    }

    /** The original location if safe, otherwise the nearest safe spot within the configured radius. */
    public Optional<Location> findNearby(Location origin) {
        World world = origin.getWorld();
        if (world == null) {
            return Optional.empty();
        }
        int baseX = origin.getBlockX();
        int baseZ = origin.getBlockZ();
        int baseY = (int) Math.floor(origin.getY());
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    for (int i = 0; i <= vertical; i++) {
                        int[] offsets = i == 0 ? new int[] {0} : new int[] {i, -i};
                        for (int dy : offsets) {
                            Location candidate = new Location(world, baseX + dx + 0.5, baseY + dy, baseZ + dz + 0.5,
                                    origin.getYaw(), origin.getPitch());
                            if (isSafe(candidate)) {
                                return Optional.of(candidate);
                            }
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static boolean obstructs(Block block, double y) {
        for (BoundingBox box : block.getCollisionShape().getBoundingBoxes()) {
            double min = block.getY() + box.getMinY();
            double max = block.getY() + box.getMaxY();
            if (max > y + EPS && min < y + HEIGHT - EPS) {
                return true;
            }
        }
        return false;
    }

    private static boolean supports(Block floor, double y) {
        for (BoundingBox box : floor.getCollisionShape().getBoundingBoxes()) {
            double top = floor.getY() + box.getMaxY();
            if (top <= y + EPS && top >= y - 0.5) {
                return true;
            }
        }
        return false;
    }
}
