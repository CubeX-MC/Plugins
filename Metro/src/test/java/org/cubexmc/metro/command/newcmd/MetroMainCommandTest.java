package org.cubexmc.metro.command.newcmd;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.cubexmc.metro.Metro;
import org.cubexmc.metro.gui.GuiManager;
import org.cubexmc.metro.manager.LanguageManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MetroMainCommandTest {

    private Metro plugin;
    private GuiManager guiManager;
    private LanguageManager languageManager;
    private MetroMainCommand command;

    @BeforeEach
    void setUp() {
        plugin = mock(Metro.class);
        guiManager = mock(GuiManager.class);
        languageManager = mock(LanguageManager.class);
        when(plugin.getGuiManager()).thenReturn(guiManager);
        when(plugin.getLanguageManager()).thenReturn(languageManager);
        command = new MetroMainCommand(plugin, null, null);
    }

    @Test
    void rootShouldOpenGuiForPermittedPlayer() {
        Player player = mock(Player.class);
        when(player.hasPermission("metro.gui")).thenReturn(true);

        command.root(player);

        verify(guiManager).openMainMenu(player);
    }

    @Test
    void rootShouldKeepGuiPermissionCheck() {
        Player player = mock(Player.class);
        when(player.hasPermission("metro.gui")).thenReturn(false);
        when(languageManager.getMessage("plugin.no_permission")).thenReturn("denied");

        command.root(player);

        verify(player).sendMessage("denied");
        verify(guiManager, never()).openMainMenu(player);
    }

    @Test
    void rootShouldShowHelpForConsole() {
        CommandSender console = mock(CommandSender.class);
        when(languageManager.getMessage("command.help_header")).thenReturn("header");
        when(languageManager.getMessage("command.help_gui")).thenReturn("gui");
        when(languageManager.getMessage("command.help_reload")).thenReturn("reload");
        when(languageManager.getMessage("command.help_line")).thenReturn("line");
        when(languageManager.getMessage("command.help_stop")).thenReturn("stop");
        when(languageManager.getMessage("command.help_portal")).thenReturn("portal");

        command.root(console);

        verify(console).sendMessage("header");
        verify(console).sendMessage("gui");
        verify(console).sendMessage("reload");
        verify(console).sendMessage("line");
        verify(console).sendMessage("stop");
        verify(console).sendMessage("portal");
        verify(guiManager, never()).openMainMenu(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void explicitHelpShouldStillShowHelpForPlayers() {
        Player player = mock(Player.class);
        when(languageManager.getMessage("command.help_header")).thenReturn("header");

        command.help(player);

        verify(player).sendMessage("header");
        verify(guiManager, never()).openMainMenu(player);
    }
}
