package com.example.rankhomes.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Immutable, validated snapshot of config.yml. Rebuilt on every reload. */
public record Settings(
        int defaultLimit,
        int maximumLimit,
        boolean capOverrides,
        String defaultRankName,
        String permissionRankName,
        String permissionPrefix,
        boolean inheritedGroups,
        String defaultHomeName,
        int maxNameLength,
        Pattern namePattern,
        Set<String> blockedWorlds,
        List<Rank> ranks,
        RankMode rankMode,
        int teleportDelay,
        int teleportCooldown,
        boolean cancelOnMove,
        boolean cancelOnDamage,
        CountdownDisplay countdownDisplay,
        boolean safetyEnabled,
        UnsafeMode unsafeMode,
        int searchRadius,
        int searchVertical,
        Set<Material> unsafeBlocks,
        Map<String, List<String>> aliases) {

    /** HIGHEST = best matching rank wins; ORDER = first matching rank in config order wins. */
    public enum RankMode { HIGHEST, ORDER }

    public enum UnsafeMode { SEARCH, DENY }

    public enum CountdownDisplay { CHAT, ACTION_BAR, TITLE, NONE }

    /** A configured rank: a LuckPerms group (and/or permission node) mapped to a home limit. */
    public record Rank(String key, String group, String permission, int limit, String displayName) {}

    public static final String[] COMMAND_KEYS = {"sethome", "home", "delhome", "homes"};

    public static Settings load(FileConfiguration c, Logger log) {
        int max = Math.max(1, c.getInt("homes.maximum-limit", 10));
        int def = c.getInt("homes.default-limit", 3);
        if (def < 0) {
            log.warning("homes.default-limit cannot be negative; using 0.");
            def = 0;
        }
        if (def > max) {
            log.warning("homes.default-limit exceeds homes.maximum-limit; using " + max + ".");
            def = max;
        }

        String defaultName = c.getString("homes.default-home-name", "home").toLowerCase(Locale.ROOT);
        int maxNameLength = Math.max(1, Math.min(64, c.getInt("homes.max-name-length", 24)));
        Pattern pattern;
        try {
            pattern = Pattern.compile(c.getString("homes.name-pattern", "[A-Za-z0-9_-]+"));
        } catch (PatternSyntaxException ex) {
            log.warning("homes.name-pattern is not a valid regex; using the default.");
            pattern = Pattern.compile("[A-Za-z0-9_-]+");
        }
        if (defaultName.length() > maxNameLength || !pattern.matcher(defaultName).matches()) {
            log.warning("homes.default-home-name violates the name rules; using 'home'.");
            defaultName = "home";
        }

        Set<String> blocked = new HashSet<>();
        for (String w : c.getStringList("homes.blocked-worlds")) {
            blocked.add(w.toLowerCase(Locale.ROOT));
        }

        List<Rank> ranks = new ArrayList<>();
        Set<String> seenGroups = new HashSet<>();
        ConfigurationSection rs = c.getConfigurationSection("ranks");
        if (rs != null) {
            // getKeys preserves file order, so the order in config.yml is the rank order.
            for (String key : rs.getKeys(false)) {
                String group = key;
                String permission = "";
                int limit;
                String display = key;
                ConfigurationSection r = rs.getConfigurationSection(key);
                if (r != null) {
                    if (!r.getBoolean("enabled", true)) {
                        continue;
                    }
                    group = r.getString("group", key);
                    permission = r.getString("permission", "");
                    limit = r.getInt("limit", 0);
                    display = r.getString("display-name", key);
                } else if (rs.isInt(key)) {
                    limit = rs.getInt(key);
                } else {
                    log.warning("Ignoring malformed rank entry '" + key + "'.");
                    continue;
                }
                group = group == null ? "" : group.trim().toLowerCase(Locale.ROOT);
                permission = permission == null ? "" : permission.trim().toLowerCase(Locale.ROOT);
                if (group.isEmpty() && permission.isEmpty()) {
                    log.warning("Rank '" + key + "' has neither a group nor a permission; ignoring it.");
                    continue;
                }
                if (limit < 0) {
                    log.warning("Rank '" + key + "' has a negative limit; ignoring it.");
                    continue;
                }
                if (limit > max) {
                    log.warning("Rank '" + key + "' limit " + limit + " exceeds homes.maximum-limit; capping to " + max + ".");
                    limit = max;
                }
                if (!group.isEmpty() && !seenGroups.add(group)) {
                    log.warning("Group '" + group + "' is used by more than one rank; check your ranks section.");
                }
                ranks.add(new Rank(key, group, permission, limit, display));
            }
        }
        RankMode rankMode = parseEnum(RankMode.class, c.getString("homes.rank-mode", "HIGHEST"),
                RankMode.HIGHEST, log, "homes.rank-mode");

        CountdownDisplay display = parseEnum(CountdownDisplay.class, c.getString("teleport.countdown-display", "ACTION_BAR"),
                CountdownDisplay.ACTION_BAR, log, "teleport.countdown-display");
        UnsafeMode mode = parseEnum(UnsafeMode.class, c.getString("teleport.safety.mode", "SEARCH"),
                UnsafeMode.SEARCH, log, "teleport.safety.mode");

        Set<Material> unsafe = EnumSet.noneOf(Material.class);
        for (String name : c.getStringList("teleport.safety.unsafe-blocks")) {
            Material m = Material.matchMaterial(name);
            if (m == null) {
                log.warning("Unknown material in teleport.safety.unsafe-blocks: " + name);
            } else {
                unsafe.add(m);
            }
        }

        Map<String, List<String>> aliases = new LinkedHashMap<>();
        for (String key : COMMAND_KEYS) {
            Set<String> clean = new LinkedHashSet<>();
            for (String a : c.getStringList("commands." + key + ".aliases")) {
                String s = a.trim().toLowerCase(Locale.ROOT);
                if (!s.isEmpty() && !s.contains(" ") && !s.equals(key)) {
                    clean.add(s);
                }
            }
            aliases.put(key, List.copyOf(clean));
        }

        return new Settings(
                def, max,
                c.getBoolean("homes.cap-overrides", true),
                c.getString("homes.default-rank-name", "&7Default"),
                c.getString("homes.permission-rank-name", "&bPermission"),
                c.getString("homes.permission-prefix", "rankhomes.homes.").toLowerCase(Locale.ROOT),
                c.getBoolean("luckperms.inherited-groups", true),
                defaultName, maxNameLength, pattern,
                Collections.unmodifiableSet(blocked),
                List.copyOf(ranks),
                rankMode,
                Math.max(0, c.getInt("teleport.delay-seconds", 3)),
                Math.max(0, c.getInt("teleport.cooldown-seconds", 5)),
                c.getBoolean("teleport.cancel-on-move", true),
                c.getBoolean("teleport.cancel-on-damage", true),
                display,
                c.getBoolean("teleport.safety.enabled", true),
                mode,
                clamp(c.getInt("teleport.safety.search-radius", 3), 0, 8),
                clamp(c.getInt("teleport.safety.search-vertical", 3), 0, 8),
                Collections.unmodifiableSet(unsafe),
                Collections.unmodifiableMap(aliases));
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback, Logger log, String path) {
        if (value != null) {
            try {
                return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        log.warning("Invalid value '" + value + "' for " + path + "; using " + fallback + ".");
        return fallback;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
