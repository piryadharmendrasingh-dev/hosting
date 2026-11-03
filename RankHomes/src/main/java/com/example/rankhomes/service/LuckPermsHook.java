package com.example.rankhomes.service;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.group.GroupManager;
import net.luckperms.api.model.user.User;
import net.luckperms.api.platform.PlayerAdapter;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The only class that touches the LuckPerms API. It is instantiated only when
 * LuckPerms is installed, so the plugin still loads without it.
 */
final class LuckPermsHook {

    private final PlayerAdapter<Player> adapter;
    private final GroupManager groupManager;

    LuckPermsHook() {
        LuckPerms api = LuckPermsProvider.get();
        this.adapter = api.getPlayerAdapter(Player.class);
        this.groupManager = api.getGroupManager();
    }

    /** Lower-case names of the player's groups (primary only, or all inherited). */
    Set<String> groupsOf(Player player, boolean inherited) {
        User user = adapter.getUser(player);
        Set<String> out = new HashSet<>();
        out.add(user.getPrimaryGroup().toLowerCase(Locale.ROOT));
        if (inherited) {
            for (Group group : user.getInheritedGroups(adapter.getQueryOptions(player))) {
                out.add(group.getName().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /** True if a group with this name is loaded in LuckPerms. */
    boolean groupExists(String name) {
        return groupManager.getGroup(name) != null;
    }
}
