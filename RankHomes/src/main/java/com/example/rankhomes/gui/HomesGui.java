package com.example.rankhomes.gui;

import com.example.rankhomes.RankHomes;
import com.example.rankhomes.config.Messages;
import com.example.rankhomes.service.LimitService;
import com.example.rankhomes.storage.Home;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Builds the /homes inventory menus. Titles, items and texts all come from config.yml. */
public final class HomesGui {

    public enum Mode { LIST, CONFIRM }

    /** Marks an inventory as ours and remembers what each slot does. */
    public static final class Holder implements InventoryHolder {
        final Mode mode;
        final int page;
        final String home;
        final Map<Integer, String> homeSlots = new HashMap<>();
        int prevSlot = -1;
        int nextSlot = -1;
        int closeSlot = -1;
        int confirmSlot = -1;
        int cancelSlot = -1;
        private Inventory inventory;

        Holder(Mode mode, int page, String home) {
            this.mode = mode;
            this.page = page;
            this.home = home;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final RankHomes plugin;

    public HomesGui(RankHomes plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("gui.enabled", true);
    }

    public void open(Player player, int requestedPage) {
        FileConfiguration cfg = plugin.getConfig();
        Messages m = plugin.messages();
        List<Home> homes = plugin.homes().getHomes(player.getUniqueId());
        LimitService.LimitResult limit = plugin.homes().getLimit(player);

        int rows = Math.max(2, Math.min(6, cfg.getInt("gui.rows", 3)));
        int perPage = (rows - 1) * 9;
        boolean showEmpty = cfg.getBoolean("gui.show-empty-slots", true);
        int entries = showEmpty ? Math.max(limit.limit(), homes.size()) : homes.size();
        int pages = Math.max(1, (entries + perPage - 1) / perPage);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));

        Holder holder = new Holder(Mode.LIST, page, null);
        Component title = m.raw(cfg.getString("gui.title", "&8Homes &7(%count%/%limit%)"),
                "%count%", homes.size(), "%limit%", limit.limit(), "%page%", page + 1, "%pages%", pages);
        Inventory inv = Bukkit.createInventory(holder, rows * 9, title);
        holder.inventory = inv;

        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= entries) {
                break;
            }
            if (index < homes.size()) {
                Home home = homes.get(index);
                inv.setItem(i, homeItem(home));
                holder.homeSlots.put(i, home.name());
            } else {
                inv.setItem(i, item("gui.empty-item", Material.LIGHT_GRAY_STAINED_GLASS_PANE, "%slot%", index + 1));
            }
        }

        int base = (rows - 1) * 9;
        if (cfg.getBoolean("gui.filler.enabled", true)) {
            ItemStack filler = item("gui.filler", Material.GRAY_STAINED_GLASS_PANE);
            for (int i = 0; i < 9; i++) {
                inv.setItem(base + i, filler);
            }
        }
        inv.setItem(base + 4, item("gui.info-item", Material.BOOK,
                "%count%", homes.size(), "%limit%", limit.limit(), "%rank%", limit.rankName()));
        holder.closeSlot = base + 8;
        inv.setItem(holder.closeSlot, item("gui.close-item", Material.BARRIER));
        if (page > 0) {
            holder.prevSlot = base + 3;
            inv.setItem(holder.prevSlot, item("gui.previous-item", Material.ARROW));
        }
        if (page < pages - 1) {
            holder.nextSlot = base + 5;
            inv.setItem(holder.nextSlot, item("gui.next-item", Material.ARROW));
        }
        player.openInventory(inv);
    }

    public void openConfirm(Player player, String homeName, int returnPage) {
        Messages m = plugin.messages();
        Holder holder = new Holder(Mode.CONFIRM, returnPage, homeName);
        Component title = m.raw(plugin.getConfig().getString("gui.confirm.title", "&8Delete home %home%?"), "%home%", homeName);
        Inventory inv = Bukkit.createInventory(holder, 27, title);
        holder.inventory = inv;
        holder.confirmSlot = 11;
        holder.cancelSlot = 15;
        inv.setItem(11, item("gui.confirm.confirm-item", Material.LIME_CONCRETE, "%home%", homeName));
        inv.setItem(13, item("gui.confirm.home-item", Material.RED_BED, "%home%", homeName));
        inv.setItem(15, item("gui.confirm.cancel-item", Material.RED_CONCRETE, "%home%", homeName));
        player.openInventory(inv);
    }

    // ------------------------------------------------------------- items

    private ItemStack homeItem(Home home) {
        World world = home.resolveWorld();
        Object[] ph = {
                "%home%", home.name(),
                "%world%", home.worldName(),
                "%x%", (int) Math.floor(home.x()),
                "%y%", (int) Math.floor(home.y()),
                "%z%", (int) Math.floor(home.z())
        };
        if (world == null) {
            return item("gui.missing-world-item", Material.BARRIER, ph);
        }
        Material icon = material("gui.environment-icons." + world.getEnvironment().name(), null);
        if (icon == null) {
            icon = material("gui.home-item.material", Material.RED_BED);
        }
        return build(icon, "gui.home-item", ph);
    }

    /** Builds an item from {@code <path>.material/name/lore}. */
    private ItemStack item(String path, Material fallback, Object... placeholders) {
        return build(material(path + ".material", fallback), path, placeholders);
    }

    private ItemStack build(Material material, String path, Object... placeholders) {
        Messages m = plugin.messages();
        FileConfiguration cfg = plugin.getConfig();
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        meta.displayName(noItalic(m.raw(cfg.getString(path + ".name", " "), placeholders)));
        List<String> loreLines = cfg.getStringList(path + ".lore");
        if (!loreLines.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : loreLines) {
                lore.add(noItalic(m.raw(line, placeholders)));
            }
            meta.lore(lore);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private Material material(String path, Material fallback) {
        String name = plugin.getConfig().getString(path);
        if (name == null) {
            return fallback;
        }
        Material found = Material.matchMaterial(name);
        if (found == null || !found.isItem() || found.isAir()) {
            plugin.getLogger().warning("Invalid item material '" + name + "' at " + path);
            return fallback;
        }
        return found;
    }

    private static Component noItalic(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
