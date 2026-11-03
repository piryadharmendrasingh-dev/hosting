package com.example.rankhomes.service;

import com.example.rankhomes.RankHomes;
import com.example.rankhomes.config.Settings;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

/**
 * Works out how many homes a player may have. Ranks come entirely from the
 * "ranks" section of config.yml (any LuckPerms group names, any order).
 *
 * <ol>
 *   <li>An admin override (/homes setlimit) wins.</li>
 *   <li>Otherwise ranks are matched by LuckPerms group and/or permission node.
 *       rank-mode HIGHEST: the best limit wins (default-limit is the floor).
 *       rank-mode ORDER: the first matching rank in config order wins.</li>
 *   <li>"rankhomes.homes.N" permission nodes are also considered.</li>
 *   <li>The result is capped by maximum-limit.</li>
 * </ol>
 */
public final class LimitService {

    /** The resulting limit and a legacy-colored label of what granted it. */
    public record LimitResult(int limit, String rankName) {}

    private final RankHomes plugin;
    private LuckPermsHook hook;

    public LimitService(RankHomes plugin) {
        this.plugin = plugin;
        refreshHook();
    }

    /** (Re)detects LuckPerms and warns about configured groups LuckPerms does not know. */
    public void refreshHook() {
        hook = null;
        if (!plugin.getServer().getPluginManager().isPluginEnabled("LuckPerms")) {
            plugin.getLogger().warning("LuckPerms not found; ranks are matched through 'group.<name>' permissions only.");
            return;
        }
        try {
            hook = new LuckPermsHook();
            plugin.getLogger().info("Hooked into LuckPerms.");
            for (Settings.Rank rank : plugin.settings().ranks()) {
                if (isGroupMissing(rank)) {
                    plugin.getLogger().warning("Rank '" + rank.key() + "' uses group '" + rank.group()
                            + "', which does not exist in LuckPerms (check the spelling in config.yml).");
                }
            }
        } catch (Throwable t) {
            hook = null;
            plugin.getLogger().log(Level.WARNING, "Could not hook into the LuckPerms API; using permission fallback.", t);
        }
    }

    /** True only when LuckPerms is available and does not know this rank's group. */
    public boolean isGroupMissing(Settings.Rank rank) {
        if (hook == null || rank.group().isEmpty()) {
            return false;
        }
        try {
            return !hook.groupExists(rank.group());
        } catch (Throwable t) {
            return false;
        }
    }

    public LimitResult resolve(Player player, Integer override) {
        Settings s = plugin.settings();
        if (override != null) {
            int limit = s.capOverrides() ? Math.min(override, s.maximumLimit()) : override;
            return new LimitResult(Math.max(0, limit), plugin.messages().text("rank-override"));
        }

        Set<String> groups = groupsOf(player, s);
        boolean orderMode = s.rankMode() == Settings.RankMode.ORDER;

        Settings.Rank matched = null;
        for (Settings.Rank rank : s.ranks()) {
            if (!matches(player, rank, groups)) {
                continue;
            }
            if (orderMode) {
                matched = rank;
                break;
            }
            if (matched == null || rank.limit() > matched.limit()) {
                matched = rank;
            }
        }

        int best = s.defaultLimit();
        String bestName = s.defaultRankName();
        if (matched != null && (orderMode || matched.limit() > best)) {
            best = matched.limit();
            bestName = matched.displayName();
        }

        String prefix = s.permissionPrefix();
        if (!prefix.isEmpty() && (!orderMode || matched == null)) {
            for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
                if (!info.getValue()) {
                    continue;
                }
                String perm = info.getPermission().toLowerCase(Locale.ROOT);
                if (!perm.startsWith(prefix)) {
                    continue;
                }
                try {
                    int n = Integer.parseInt(perm.substring(prefix.length()));
                    if (n > best) {
                        best = n;
                        bestName = s.permissionRankName();
                    }
                } catch (NumberFormatException ignored) {
                    // e.g. a wildcard node such as "rankhomes.homes.*"
                }
            }
        }
        return new LimitResult(Math.min(best, s.maximumLimit()), bestName);
    }

    private boolean matches(Player player, Settings.Rank rank, Set<String> groups) {
        if (!rank.group().isEmpty() && groups.contains(rank.group())) {
            return true;
        }
        return !rank.permission().isEmpty() && player.hasPermission(rank.permission());
    }

    private Set<String> groupsOf(Player player, Settings s) {
        Set<String> groups = new HashSet<>();
        if (hook != null) {
            try {
                groups.addAll(hook.groupsOf(player, s.inheritedGroups()));
                if (!s.inheritedGroups()) {
                    return groups;
                }
            } catch (Throwable t) {
                // user data not loaded yet etc. - fall back to permission checks below
                plugin.getLogger().log(Level.FINE, "LuckPerms group lookup failed for " + player.getName(), t);
            }
        }
        // LuckPerms exposes every inherited group as "group.<name>"; this also covers the no-LuckPerms case.
        for (Settings.Rank rank : s.ranks()) {
            if (!rank.group().isEmpty() && player.hasPermission("group." + rank.group())) {
                groups.add(rank.group());
            }
        }
        return groups;
    }
}
