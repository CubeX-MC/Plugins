package org.cubexmc.metro.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;

class AdventureUtilTest {

    @Test
    void shouldSendLegacyAmpersandTitlesAndActionBarsThroughAdventure() {
        Player player = mock(Player.class);
        Player.Spigot spigot = mock(Player.Spigot.class);
        when(player.spigot()).thenReturn(spigot);
        LegacyComponentSerializer section = LegacyComponentSerializer.legacySection();

        AdventureUtil.sendTitle(player, "&eNext", "&bStation", 5, 40, 5);
        AdventureUtil.sendActionBar(player, "&aReady");

        verify(player).sendTitle("§eNext", "§bStation", 5, 40, 5);
        verify(spigot).sendMessage(eq(ChatMessageType.ACTION_BAR), any(BaseComponent[].class));

        Component parsed = AdventureUtil.component("&aReady");
        assertEquals("§aReady", section.serialize(parsed));
    }
}
