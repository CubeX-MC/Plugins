package org.cubexmc.metro.command.newcmd;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;

import org.incendo.cloud.annotations.Command;
import org.junit.jupiter.api.Test;

class CommandAliasRegressionTest {

    @Test
    void cloudCommandAnnotationsExposeRailwayRootsOnly() {
        for (Class<?> commandClass : List.of(
                MetroMainCommand.class,
                LineCommand.class,
                StopCommand.class,
                PortalCommand.class)) {
            for (Method method : commandClass.getDeclaredMethods()) {
                for (Command command : method.getAnnotationsByType(Command.class)) {
                    String value = command.value();
                    assertTrue(value.startsWith("rw|railway|rail"), () -> commandClass.getSimpleName() + "#" + method.getName() + " exposes: " + value);
                    assertFalse(value.startsWith("m|metro"), () -> commandClass.getSimpleName() + "#" + method.getName() + " exposes: " + value);
                    assertFalse(value.contains("|m|metro"), () -> commandClass.getSimpleName() + "#" + method.getName() + " exposes: " + value);
                }
            }
        }
    }
}
