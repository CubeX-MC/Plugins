package org.cubexmc.ecobalancer.commands;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.cubexmc.config.ReloadChain;
import org.cubexmc.config.ReloadReport;
import org.cubexmc.core.Reloadable;
import org.cubexmc.ecobalancer.EcoBalancer;
import org.junit.jupiter.api.Test;

import java.util.Map;

class UtilCommandReloadTest {
    @Test
    void failedReloadNamesStageAndDoesNotClaimSuccess() {
        EcoBalancer plugin = mock(EcoBalancer.class);
        CommandSender sender = mock(CommandSender.class);
        Command command = mock(Command.class);
        when(sender.hasPermission("ecobalancer.command.reload")).thenReturn(true);
        ReloadReport report = ReloadChain.create()
                .add("language", (Reloadable) () -> { throw new IllegalStateException("broken language file"); })
                .run();
        when(plugin.reloadConfiguration()).thenReturn(report);
        when(plugin.getFormattedMessage("messages.reload_failed", Map.of("stage", "language")))
                .thenReturn("Reload failed at language");

        assertTrue(new UtilCommand(plugin).onCommand(sender, command, "ecobal", new String[] {"reload"}));

        verify(sender).sendMessage("Reload failed at language");
        verify(plugin, never()).getFormattedMessage("messages.reload_success", null);
    }
}
