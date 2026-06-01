package org.cubexmc.mountlicense.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.cubexmc.mountlicense.MountLicensePlugin;
import org.cubexmc.mountlicense.lang.LanguageManager;
import org.junit.jupiter.api.Test;

class MountLicenseCommandTest {

    @Test
    void usePermissionCoversBasicQueryCommands() {
        assertTrue(MountLicenseCommand.requiresUsePermission("help"));
        assertTrue(MountLicenseCommand.requiresUsePermission("list"));
        assertTrue(MountLicenseCommand.requiresUsePermission("info"));
        assertTrue(MountLicenseCommand.requiresUsePermission("locate"));
        assertFalse(MountLicenseCommand.requiresUsePermission("recall"));
        assertFalse(MountLicenseCommand.requiresUsePermission("admin"));
    }

    @Test
    void listIsRejectedBeforeVehicleLookupWithoutUsePermission() {
        MountLicensePlugin plugin = mock(MountLicensePlugin.class);
        LanguageManager language = mock(LanguageManager.class);
        Player sender = mock(Player.class);
        Command command = mock(Command.class);

        when(plugin.languageManager()).thenReturn(language);
        when(sender.hasPermission("mountlicense.use")).thenReturn(false);

        MountLicenseCommand executor = new MountLicenseCommand(plugin);
        assertTrue(executor.onCommand(sender, command, "ml", new String[] {"list"}));

        verify(language).send(sender, "commands.no_permission");
        verify(plugin, never()).vehicleIndex();
    }

    @Test
    void adminReloadDelegatesToPluginReloadAll() {
        MountLicensePlugin plugin = mock(MountLicensePlugin.class);
        LanguageManager language = mock(LanguageManager.class);
        Player sender = mock(Player.class);
        Command command = mock(Command.class);

        when(plugin.languageManager()).thenReturn(language);
        when(sender.hasPermission("mountlicense.admin.reload")).thenReturn(true);

        MountLicenseCommand executor = new MountLicenseCommand(plugin);
        assertTrue(executor.onCommand(sender, command, "ml", new String[] {"admin", "reload"}));

        verify(plugin).reloadAll();
        verify(language).send(sender, "admin.reload.success");
    }
}
