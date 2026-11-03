package com.example.rankhomes.gui;

import com.example.rankhomes.RankHomes;
import com.example.rankhomes.service.DataUnavailableException;
import com.example.rankhomes.storage.Home;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

import java.util.Optional;

/** Handles clicks inside the homes menus. Nothing can ever be taken out of them. */
public final class GuiListener implements Listener {

    private final RankHomes plugin;

    public GuiListener(RankHomes plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof HomesGui.Holder holder)) {
            return;
        }
        event.setCancelled(true); // also blocks shift-clicking items into the menu
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() == null
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getSlot();
        ClickType click = event.getClick();

        // Inventory changes are deferred one tick so we never mutate a menu inside its own click event.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                if (holder.mode == HomesGui.Mode.CONFIRM) {
                    handleConfirm(player, holder, slot);
                } else {
                    handleList(player, holder, slot, click);
                }
            } catch (DataUnavailableException ex) {
                plugin.messages().send(player, "data-error");
                player.closeInventory();
            }
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof HomesGui.Holder) {
            event.setCancelled(true);
        }
    }

    private void handleList(Player player, HomesGui.Holder holder, int slot, ClickType click) {
        if (slot == holder.closeSlot) {
            player.closeInventory();
            return;
        }
        if (slot == holder.prevSlot) {
            plugin.gui().open(player, holder.page - 1);
            return;
        }
        if (slot == holder.nextSlot) {
            plugin.gui().open(player, holder.page + 1);
            return;
        }
        String name = holder.homeSlots.get(slot);
        if (name == null) {
            return;
        }
        Optional<Home> home = plugin.homes().getHome(player.getUniqueId(), name);
        if (home.isEmpty()) { // deleted elsewhere while the menu was open
            plugin.gui().open(player, holder.page);
            return;
        }
        if (click.isLeftClick()) {
            player.closeInventory();
            plugin.teleports().start(player, home.get());
        } else if (click.isRightClick()) {
            if (plugin.getConfig().getBoolean("gui.confirm-delete", true)) {
                plugin.gui().openConfirm(player, name, holder.page);
            } else {
                deleteAndReopen(player, name, holder.page);
            }
        }
    }

    private void handleConfirm(Player player, HomesGui.Holder holder, int slot) {
        if (slot == holder.confirmSlot) {
            deleteAndReopen(player, holder.home, holder.page);
        } else if (slot == holder.cancelSlot) {
            plugin.gui().open(player, holder.page);
        }
    }

    private void deleteAndReopen(Player player, String name, int page) {
        if (plugin.homes().deleteHome(player.getUniqueId(), name)) {
            plugin.messages().send(player, "home-deleted", "%home%", name);
        } else {
            plugin.messages().send(player, "home-not-found", "%home%", name);
        }
        plugin.gui().open(player, page);
    }
}
