package com.example.rankhomes.command;

import com.example.rankhomes.RankHomes;
import com.example.rankhomes.config.Messages;
import com.example.rankhomes.config.Settings;
import com.example.rankhomes.service.DataUnavailableException;
import com.example.rankhomes.service.HomeService;
import com.example.rankhomes.service.LimitService;
import com.example.rankhomes.storage.Home;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * One class backs /sethome, /home, /delhome and /homes. Commands are registered
 * at runtime (see RankHomes#registerCommands) so aliases can come from config.yml.
 */
public final class HomeCommand extends Command {

    public enum Type {
        SETHOME("sethome", "Save a home at your location", "/sethome [name]"),
        HOME("home", "Teleport to one of your homes", "/home [name]"),
        DELHOME("delhome", "Delete one of your homes", "/delhome [name]"),
        HOMES("homes", "Open your homes menu", "/homes [list|ranks|reload|setlimit]");

        public final String label;
        final String description;
        final String usage;

        Type(String label, String description, String usage) {
            this.label = label;
            this.description = description;
            this.usage = usage;
        }
    }

    /** Hard ceiling for /homes setlimit when homes.cap-overrides is false. */
    private static final int ABSOLUTE_MAX_OVERRIDE = 1000;

    private final RankHomes plugin;
    private final Type type;

    public HomeCommand(RankHomes plugin, Type type, List<String> aliases) {
        super(type.label, type.description, type.usage, aliases);
        this.plugin = plugin;
        this.type = type;
        setPermission("rankhomes.use");
    }

    // ------------------------------------------------------------ execution

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        Messages m = plugin.messages();
        if (!sender.hasPermission("rankhomes.use")) {
            m.send(sender, "no-permission");
            return true;
        }
        try {
            switch (type) {
                case HOMES -> homes(sender, args);
                case SETHOME -> withPlayer(sender, p -> setHome(p, args));
                case HOME -> withPlayer(sender, p -> goHome(p, args));
                case DELHOME -> withPlayer(sender, p -> delHome(p, args));
            }
        } catch (DataUnavailableException ex) {
            m.send(sender, "data-error");
        }
        return true;
    }

    private void withPlayer(CommandSender sender, java.util.function.Consumer<Player> action) {
        if (sender instanceof Player player) {
            action.accept(player);
        } else {
            plugin.messages().send(sender, "players-only");
        }
    }

    private void setHome(Player player, String[] args) {
        Messages m = plugin.messages();
        if (args.length > 1) {
            m.send(player, "usage-sethome");
            return;
        }
        String name = resolveName(args);
        if (name == null) {
            m.send(player, "invalid-name", "%max%", plugin.settings().maxNameLength());
            return;
        }
        HomeService.SetResult result = plugin.homes().setHome(player, name);
        switch (result) {
            case CREATED -> m.send(player, "home-set", "%home%", name);
            case UPDATED -> m.send(player, "home-updated", "%home%", name);
            case LIMIT_REACHED -> m.send(player, "limit-reached", "%limit%", plugin.homes().getLimit(player).limit());
            case BLOCKED_WORLD -> m.send(player, "blocked-world", "%world%", player.getWorld().getName());
        }
    }

    private void goHome(Player player, String[] args) {
        Messages m = plugin.messages();
        if (args.length > 1) {
            m.send(player, "usage-home");
            return;
        }
        UUID id = player.getUniqueId();
        if (args.length == 0) {
            String defaultName = plugin.settings().defaultHomeName();
            Optional<Home> byDefault = plugin.homes().getHome(id, defaultName);
            if (byDefault.isPresent()) {
                plugin.teleports().start(player, byDefault.get());
                return;
            }
            List<Home> all = plugin.homes().getHomes(id);
            if (all.isEmpty()) {
                m.send(player, "no-homes");
            } else if (all.size() == 1) {
                plugin.teleports().start(player, all.get(0));
            } else if (plugin.gui().isEnabled()) {
                plugin.gui().open(player, 0);
            } else {
                sendList(player);
            }
            return;
        }
        String name = resolveName(args);
        if (name == null) {
            m.send(player, "invalid-name", "%max%", plugin.settings().maxNameLength());
            return;
        }
        Optional<Home> home = plugin.homes().getHome(id, name);
        if (home.isEmpty()) {
            m.send(player, "home-not-found", "%home%", name);
        } else {
            plugin.teleports().start(player, home.get());
        }
    }

    private void delHome(Player player, String[] args) {
        Messages m = plugin.messages();
        if (args.length > 1) {
            m.send(player, "usage-delhome");
            return;
        }
        String name = resolveName(args);
        if (name == null) {
            m.send(player, "invalid-name", "%max%", plugin.settings().maxNameLength());
            return;
        }
        if (plugin.homes().deleteHome(player.getUniqueId(), name)) {
            m.send(player, "home-deleted", "%home%", name);
        } else {
            m.send(player, "home-not-found", "%home%", name);
        }
    }

    private void homes(CommandSender sender, String[] args) {
        Messages m = plugin.messages();
        if (args.length == 0) {
            withPlayer(sender, player -> {
                if (plugin.gui().isEnabled()) {
                    plugin.gui().open(player, 0);
                } else {
                    sendList(player);
                }
            });
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> withPlayer(sender, this::sendList);
            case "reload" -> {
                if (!sender.hasPermission("rankhomes.admin")) {
                    m.send(sender, "no-permission");
                    return;
                }
                plugin.reloadAll();
                m.send(sender, "reloaded");
            }
            case "setlimit" -> {
                if (!sender.hasPermission("rankhomes.admin")) {
                    m.send(sender, "no-permission");
                    return;
                }
                setLimit(sender, args);
            }
            case "ranks" -> {
                if (!sender.hasPermission("rankhomes.admin")) {
                    m.send(sender, "no-permission");
                    return;
                }
                sendRanks(sender);
            }
            default -> m.send(sender, "usage-homes");
        }
    }

    private void setLimit(CommandSender sender, String[] args) {
        Messages m = plugin.messages();
        Settings s = plugin.settings();
        if (args.length != 3) {
            m.send(sender, "usage-setlimit");
            return;
        }
        OfflinePlayer target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            target = Bukkit.getOfflinePlayerIfCached(args[1]);
        }
        if (target == null) {
            m.send(sender, "player-not-found", "%player%", args[1]);
            return;
        }
        String shown = target.getName() != null ? target.getName() : args[1];

        if (args[2].equalsIgnoreCase("reset")) {
            plugin.homes().setLimitOverride(target.getUniqueId(), null);
            m.send(sender, "limit-reset", "%player%", shown);
            return;
        }
        int max = s.capOverrides() ? s.maximumLimit() : ABSOLUTE_MAX_OVERRIDE;
        int amount;
        try {
            amount = Integer.parseInt(args[2]);
        } catch (NumberFormatException ex) {
            m.send(sender, "invalid-amount", "%max%", max);
            return;
        }
        if (amount < 0 || amount > max) {
            m.send(sender, "invalid-amount", "%max%", max);
            return;
        }
        plugin.homes().setLimitOverride(target.getUniqueId(), amount);
        m.send(sender, "limit-set", "%player%", shown, "%limit%", amount);
    }

    /** Shows the ranks exactly as loaded from config.yml, in order. */
    private void sendRanks(CommandSender sender) {
        Messages m = plugin.messages();
        Settings s = plugin.settings();
        if (s.ranks().isEmpty()) {
            m.send(sender, "ranks-empty", "%default%", s.defaultLimit());
            return;
        }
        m.send(sender, "ranks-header", "%count%", s.ranks().size(), "%mode%", s.rankMode().name(),
                "%default%", s.defaultLimit(), "%max%", s.maximumLimit());
        int index = 1;
        for (Settings.Rank rank : s.ranks()) {
            String warning = plugin.limits().isGroupMissing(rank) ? m.text("ranks-group-missing") : "";
            m.send(sender, "ranks-entry",
                    "%index%", index++, "%key%", rank.key(),
                    "%group%", rank.group().isEmpty() ? "-" : rank.group(),
                    "%permission%", rank.permission().isEmpty() ? "-" : rank.permission(),
                    "%limit%", rank.limit(), "%name%", rank.displayName(), "%warning%", warning);
        }
    }

    private void sendList(Player player) {
        Messages m = plugin.messages();
        List<Home> homes = plugin.homes().getHomes(player.getUniqueId());
        LimitService.LimitResult limit = plugin.homes().getLimit(player);
        if (homes.isEmpty()) {
            m.send(player, "no-homes");
            return;
        }
        m.send(player, "homes-header", "%count%", homes.size(), "%limit%", limit.limit());
        for (Home h : homes) {
            boolean available = h.resolveWorld() != null;
            m.send(player, available ? "homes-entry" : "homes-entry-missing-world",
                    "%home%", h.name(), "%world%", h.worldName(),
                    "%x%", (int) Math.floor(h.x()), "%y%", (int) Math.floor(h.y()), "%z%", (int) Math.floor(h.z()));
        }
    }

    /** Lower-cased, validated home name, or null if it breaks the configured rules. */
    private String resolveName(String[] args) {
        Settings s = plugin.settings();
        String name = (args.length == 0 ? s.defaultHomeName() : args[0]).toLowerCase(Locale.ROOT);
        if (name.length() > s.maxNameLength() || !s.namePattern().matcher(name).matches()) {
            return null;
        }
        return name;
    }

    // ------------------------------------------------------- tab completion

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (!sender.hasPermission("rankhomes.use")) {
            return List.of();
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        try {
            switch (type) {
                case HOME, DELHOME, SETHOME -> {
                    if (args.length == 1 && sender instanceof Player p) {
                        plugin.homes().getHomes(p.getUniqueId()).forEach(h -> options.add(h.name()));
                    }
                }
                case HOMES -> {
                    if (args.length == 1) {
                        options.add("list");
                        if (sender.hasPermission("rankhomes.admin")) {
                            options.add("reload");
                            options.add("setlimit");
                            options.add("ranks");
                        }
                    } else if (sender.hasPermission("rankhomes.admin") && args[0].equalsIgnoreCase("setlimit")) {
                        if (args.length == 2) {
                            Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
                        } else if (args.length == 3) {
                            options.add("reset");
                        }
                    }
                }
            }
        } catch (DataUnavailableException ignored) {
            return List.of();
        }
        options.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(last));
        return options;
    }
}
