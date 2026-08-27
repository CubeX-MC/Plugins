package org.cubexmc.contract.command;

import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.cubexmc.contract.ContractPlugin;
import org.cubexmc.contract.config.LanguageManager;
import org.cubexmc.contract.gui.ContractGui;
import org.cubexmc.core.Messager;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ContractCommandSaleTest {
    private final ContractPlugin plugin = mock(ContractPlugin.class);
    private final LanguageManager lang = mock(LanguageManager.class);
    private final Messager messager = mock(Messager.class);
    private final ContractGui gui = mock(ContractGui.class);
    private final ContractCommand commandHandler = new ContractCommand(plugin);

    ContractCommandSaleTest() {
        when(plugin.lang()).thenReturn(lang);
        when(plugin.messager()).thenReturn(messager);
        when(plugin.gui()).thenReturn(gui);
    }

    @Test
    void saleCommandRequiresCreatePermission() {
        Player player = mock(Player.class);
        when(player.hasPermission("contract.create")).thenReturn(false);
        when(lang.message("no-permission")).thenReturn("denied");

        commandHandler.onCommand(player, mock(Command.class), "contract",
            new String[]{"sale", "Bob", "125", "3", "Diamonds"});

        verify(messager).send(player, "denied");
        verifyNoInteractions(gui);
    }

    @Test
    void invalidSaleUsageReturnsTheLocalizedSyntax() {
        Player player = mock(Player.class);
        when(player.hasPermission("contract.create")).thenReturn(true);
        when(lang.ui(eq("usage-sale"), anyMap())).thenReturn("/contract sale <buyer> <price> <days> <title>");
        when(lang.message(eq("invalid-usage"), anyMap())).thenReturn("invalid sale usage");

        commandHandler.onCommand(player, mock(Command.class), "contract", new String[]{"sale"});

        verify(messager).send(player, "invalid sale usage");
        verifyNoInteractions(gui);
    }

    @Test
    void validSaleCommandOpensTheSharedSigningConfirmation() {
        Player player = mock(Player.class);
        when(player.hasPermission("contract.create")).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        commandHandler.onCommand(player, mock(Command.class), "contract",
            new String[]{"sale", "Bob", "125", "3", "Four diamonds", "|", "Direct sale"});

        verify(gui).confirmSaleCommand(player, "Bob", 125.0, 3, "Four diamonds ", " Direct sale", null);
    }
}
