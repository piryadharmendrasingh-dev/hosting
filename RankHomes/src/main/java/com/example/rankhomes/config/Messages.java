package com.example.rankhomes.config;

import com.example.rankhomes.RankHomes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;

/** Reads messages from config.yml and turns them into Adventure components. */
public final class Messages {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    private final RankHomes plugin;

    public Messages(RankHomes plugin) {
        this.plugin = plugin;
    }

    /** Raw configured text for {@code messages.<key>}. */
    public String text(String key) {
        return plugin.getConfig().getString("messages." + key, "&cMissing message: " + key);
    }

    /** Deserialises arbitrary text; {@code placeholders} are alternating token/value pairs. */
    public Component raw(String text, Object... placeholders) {
        return LEGACY.deserialize(apply(text, placeholders));
    }

    public Component component(String key, Object... placeholders) {
        return raw(text(key), placeholders);
    }

    /** Sends a message; an empty configured message is skipped so admins can silence it. */
    public void send(CommandSender to, String key, Object... placeholders) {
        String text = text(key);
        if (text.isEmpty()) {
            return;
        }
        to.sendMessage(raw(text, placeholders));
    }

    private String apply(String text, Object... placeholders) {
        String out = text.replace("%prefix%", plugin.getConfig().getString("messages.prefix", ""));
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            out = out.replace(String.valueOf(placeholders[i]), String.valueOf(placeholders[i + 1]));
        }
        return out;
    }
}
