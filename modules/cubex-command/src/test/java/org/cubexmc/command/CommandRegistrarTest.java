package org.cubexmc.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;
import org.cubexmc.core.Terminable;
import org.cubexmc.core.TerminableConsumer;
import org.junit.jupiter.api.Test;

class CommandRegistrarTest {

    /** An executor that is also a tab completer, like most of this repo's command classes. */
    private interface CompletingExecutor extends CommandExecutor, TabCompleter {}

    private JavaPlugin plugin() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CommandRegistrarTest"));
        when(plugin.getServer()).thenReturn(mock(Server.class));
        return plugin;
    }

    private SimpleCommandMap newCommandMap() {
        return new SimpleCommandMap(mock(Server.class));
    }

    @Test
    void shouldWireExecutorAndUseItAsCompleterWhenItIsOne() {
        JavaPlugin plugin = plugin();
        PluginCommand command = mock(PluginCommand.class);
        when(plugin.getCommand("ct")).thenReturn(command);

        CompletingExecutor executor = mock(CompletingExecutor.class);
        assertEquals(command, new CommandRegistrar(plugin).registerPluginCommand("ct", executor));

        verify(command).setExecutor(executor);
        verify(command).setTabCompleter(executor);
    }

    @Test
    void shouldNotInventACompleterForAPlainExecutor() {
        JavaPlugin plugin = plugin();
        PluginCommand command = mock(PluginCommand.class);
        when(plugin.getCommand("eb")).thenReturn(command);

        CommandExecutor executor = mock(CommandExecutor.class);
        new CommandRegistrar(plugin).registerPluginCommand("eb", executor);

        verify(command).setExecutor(executor);
        verify(command, never()).setTabCompleter(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldNotSwallowACommandMissingFromPluginYml() {
        JavaPlugin plugin = plugin();
        when(plugin.getCommand(anyString())).thenReturn(null);

        // Default policy reports and returns null rather than failing silently.
        assertNull(new CommandRegistrar(plugin).registerPluginCommand("ghost", mock(CommandExecutor.class)));

        CommandRegistrar strict = new CommandRegistrar(plugin, null, MissingCommandPolicy.THROW, null);
        assertThrows(
                IllegalStateException.class,
                () -> strict.registerPluginCommand("ghost", mock(CommandExecutor.class)));
    }

    @Test
    void shouldRegisterADynamicCommandAndRemoveItWhenTheHandleCloses() throws Exception {
        SimpleCommandMap map = newCommandMap();
        Command command = new StubCommand("fawereplace");

        CommandRegistrar registrar = new CommandRegistrar(plugin(), null, MissingCommandPolicy.WARN, map);
        Terminable handle = registrar.registerDynamicCommand("fawereplace", command);

        assertNotNull(handle);
        assertNotNull(map.getCommand("fawereplace"));

        handle.close();
        assertNull(map.getCommand("fawereplace"));
    }

    @Test
    void shouldRemoveAliasesButLeaveAnotherPluginsCommandAlone() {
        SimpleCommandMap map = newCommandMap();
        Command mine = new StubCommand("dupe", List.of("dupealias"));
        Command theirs = new StubCommand("theirs");
        map.register("mine", mine);
        map.register("theirs", theirs);

        List<String> removed = CommandMaps.unregister(map, mine, null);

        assertTrue(removed.contains("dupe"));
        assertTrue(removed.contains("dupealias"));
        assertNull(map.getCommand("dupe"));
        assertNull(map.getCommand("dupealias"));
        // The other plugin's command survives untouched.
        assertEquals(theirs, map.getCommand("theirs"));
    }

    @Test
    void shouldStillUnregisterACommandThatOnlyWonItsPrefixedAlias() throws Exception {
        SimpleCommandMap map = newCommandMap();
        map.register("first", new StubCommand("clash"));

        // The second registration loses the bare label and lands as "second:clash" only.
        Command mine = new StubCommand("clash");
        CommandRegistrar registrar = new CommandRegistrar(plugin(), null, MissingCommandPolicy.WARN, map);
        Terminable handle = registrar.registerDynamicCommand("second", mine);

        assertNotNull(handle);
        assertNotNull(map.getCommand("second:clash"));

        handle.close();
        assertNull(map.getCommand("second:clash"));
        // The first plugin keeps the bare label.
        assertNotNull(map.getCommand("clash"));
    }

    @Test
    void shouldBindTheHandleToTheTerminableConsumerWhenGiven() {
        SimpleCommandMap map = newCommandMap();
        RecordingTerminables terminables = new RecordingTerminables();

        new CommandRegistrar(plugin(), terminables, MissingCommandPolicy.WARN, map)
                .registerDynamicCommand("rulegems", new StubCommand("proxy"));

        assertEquals(1, terminables.bound.size());
        assertNotNull(map.getCommand("proxy"));

        terminables.closeAll();
        assertNull(map.getCommand("proxy"));
    }

    @Test
    void shouldReportWhenTheCommandMapCannotBeReached() {
        // A Server with no reachable `commandMap` field: fail loudly with null, never throw.
        JavaPlugin plugin = plugin();
        assertNull(CommandMaps.resolve(plugin.getServer(), null));
        assertNull(new CommandRegistrar(plugin).registerDynamicCommand("x", new StubCommand("x")));
    }

    private static final class RecordingTerminables implements TerminableConsumer {
        private final List<AutoCloseable> bound = new ArrayList<>();

        @Override
        public <T extends AutoCloseable> T bind(T terminable) {
            bound.add(terminable);
            return terminable;
        }

        @Override
        public Terminable bind(Runnable closeAction) {
            Terminable terminable = Terminable.of(closeAction);
            bound.add(terminable);
            return terminable;
        }

        void closeAll() {
            for (AutoCloseable closeable : bound) {
                try {
                    closeable.close();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }
        }
    }

    private static final class StubCommand extends Command {
        StubCommand(String name) {
            super(name);
        }

        StubCommand(String name, List<String> aliases) {
            super(name, "", "/" + name, aliases);
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            return true;
        }
    }
}
