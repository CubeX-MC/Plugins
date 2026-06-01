package org.cubexmc.metro.util;

import java.time.Duration;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

/**
 * Utility helpers for sending Adventure-based UI elements (titles, action bars, etc.).
 */
public final class AdventureUtil {

    private static final LegacyComponentSerializer LEGACY_AMPERSAND = LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.legacySection();

    private AdventureUtil() {
        // no instances
    }

    public static Component component(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        String normalized = text.replace('§', '&');
        return LEGACY_AMPERSAND.deserialize(normalized);
    }

    public static Title buildTitle(String title, String subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        return Title.title(
                component(title),
                component(subtitle),
                Title.Times.times(
                        ticksToDuration(fadeInTicks),
                        ticksToDuration(stayTicks),
                        ticksToDuration(fadeOutTicks)));
    }

    public static void sendTitle(Player player, String title, String subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        if (player == null) {
            return;
        }
        player.sendTitle(legacyText(title), legacyText(subtitle), fadeInTicks, stayTicks, fadeOutTicks);
    }

    public static void sendActionBar(Player player, String message) {
        if (player == null || message == null || message.isEmpty()) {
            return;
        }
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(legacyText(message)));
    }

    public static void clearTitle(Player player) {
        if (player == null) {
            return;
        }
        player.resetTitle();
    }

    public static String legacyText(String text) {
        return LEGACY_SECTION.serialize(component(text));
    }

    private static Duration ticksToDuration(int ticks) {
        if (ticks <= 0) {
            return Duration.ZERO;
        }
        return Duration.ofMillis(ticks * 50L);
    }
}


