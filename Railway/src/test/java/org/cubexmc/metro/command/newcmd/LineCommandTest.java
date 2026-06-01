package org.cubexmc.metro.command.newcmd;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.entity.Player;
import org.cubexmc.metro.Metro;
import org.cubexmc.metro.control.TrainControlMode;
import org.cubexmc.metro.manager.LanguageManager;
import org.cubexmc.metro.manager.LineManager;
import org.cubexmc.metro.manager.StopManager;
import org.cubexmc.metro.model.Line;
import org.cubexmc.metro.service.LineService;
import org.cubexmc.metro.service.LineServiceManager;
import org.junit.jupiter.api.Test;

class LineCommandTest {

    @Test
    void shouldNotDeleteLineWithoutExplicitConfirmation() {
        Metro plugin = mock(Metro.class);
        LanguageManager languageManager = mock(LanguageManager.class);
        LineManager lineManager = mock(LineManager.class);
        StopManager stopManager = mock(StopManager.class);
        Player player = mock(Player.class);
        Line line = new Line("red", "Red");

        when(plugin.getLanguageManager()).thenReturn(languageManager);
        when(lineManager.getLine("red")).thenReturn(line);
        when(player.hasPermission("railway.admin")).thenReturn(false);
        when(player.isOp()).thenReturn(true);
        when(languageManager.getMessage("command.confirm_required")).thenReturn("confirm required");
        when(languageManager.getMessage(eq("command.confirm_hint"), anyMap())).thenReturn("run command");

        new LineCommand(plugin, lineManager, stopManager).delete(player, "red", null);

        verify(lineManager, never()).deleteLine("red");
        verify(player).sendMessage("confirm required");
        verify(player).sendMessage("run command");
    }

    @Test
    void shouldSetControlModeAndRefreshActiveService() {
        Metro plugin = mock(Metro.class);
        LanguageManager languageManager = mock(LanguageManager.class);
        LineManager lineManager = mock(LineManager.class);
        StopManager stopManager = mock(StopManager.class);
        LineServiceManager serviceManager = mock(LineServiceManager.class);
        LineService service = mock(LineService.class);
        Player player = mock(Player.class);
        Line line = new Line("red", "Red");

        when(plugin.getLanguageManager()).thenReturn(languageManager);
        when(plugin.getLineServiceManager()).thenReturn(serviceManager);
        when(lineManager.getLine("red")).thenReturn(line);
        when(player.hasPermission("railway.admin")).thenReturn(true);
        when(serviceManager.getService("red")).thenReturn(service);
        when(languageManager.getMessage(eq("line.control_success"), anyMap())).thenReturn("mode set");
        doAnswer(invocation -> {
            line.setControlMode(invocation.getArgument(1));
            return true;
        }).when(lineManager).setLineControlMode(eq("red"), eq(TrainControlMode.KINEMATIC));

        new LineCommand(plugin, lineManager, stopManager).setControlMode(player, "red", "kinematic");

        verify(lineManager).setLineControlMode("red", TrainControlMode.KINEMATIC);
        verify(service).refreshPhysicsEngines();
        verify(player).sendMessage("mode set");
    }

    @Test
    void shouldSetEntityTypeAndRefreshActiveService() {
        Metro plugin = mock(Metro.class);
        LanguageManager languageManager = mock(LanguageManager.class);
        LineManager lineManager = mock(LineManager.class);
        StopManager stopManager = mock(StopManager.class);
        LineServiceManager serviceManager = mock(LineServiceManager.class);
        LineService service = mock(LineService.class);
        Player player = mock(Player.class);
        Line line = new Line("red", "Red");

        when(plugin.getLanguageManager()).thenReturn(languageManager);
        when(plugin.getLineServiceManager()).thenReturn(serviceManager);
        when(plugin.getTrainSpacing()).thenReturn(1.6D);
        when(lineManager.getLine("red")).thenReturn(line);
        when(player.hasPermission("railway.admin")).thenReturn(true);
        when(serviceManager.getService("red")).thenReturn(service);
        when(languageManager.getMessage(eq("line.setentity_success"), anyMap())).thenReturn("entity set");
        doAnswer(invocation -> {
            line.setEntityType(invocation.getArgument(1));
            return true;
        }).when(lineManager).setLineEntityType(eq("red"), eq("PIG"));

        new LineCommand(plugin, lineManager, stopManager).setEntity(player, "red", "pig");

        verify(lineManager).setLineEntityType("red", "PIG");
        verify(service).refreshEntityModels();
        verify(player).sendMessage("entity set");
    }
}
