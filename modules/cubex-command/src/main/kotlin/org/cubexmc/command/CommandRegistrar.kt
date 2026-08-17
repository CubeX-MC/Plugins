package org.cubexmc.command

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandMap
import org.bukkit.command.PluginCommand
import org.bukkit.command.TabCompleter
import org.bukkit.plugin.java.JavaPlugin
import org.cubexmc.core.Terminable
import org.cubexmc.core.TerminableConsumer

/** What to do when `plugin.yml` does not declare a command that the code tries to register. */
enum class MissingCommandPolicy {
    /** Log a SEVERE line and return null. The plugin keeps running with a dead command. */
    WARN,

    /** Throw [IllegalStateException]. Under `CubexPlugin` this aborts enable, which is usually right. */
    THROW,
}

/**
 * Registration helpers for the two command shapes in this repo: commands declared in `plugin.yml`,
 * and commands created at runtime and pushed into the server [org.bukkit.command.CommandMap].
 *
 * Deliberately not included: a command DSL, subcommand routing, and any wrapper over the Cloud
 * framework. Those stay in the plugins that use them.
 */
class CommandRegistrar @JvmOverloads constructor(
    private val plugin: JavaPlugin,
    private val terminables: TerminableConsumer? = null,
    private val missingCommandPolicy: MissingCommandPolicy = MissingCommandPolicy.WARN,
    /**
     * The command map to register dynamic commands into. Left null it is resolved from the server
     * on first use; pass one when the caller already holds a cached map (as RuleGems does).
     */
    private val commandMap: CommandMap? = null,
) {
    /**
     * Wires a `plugin.yml` command to [executor], and to [tabCompleter] when given. A missing
     * declaration is never swallowed silently — see [MissingCommandPolicy].
     */
    @JvmOverloads
    fun registerPluginCommand(
        name: String,
        executor: CommandExecutor,
        tabCompleter: TabCompleter? = null,
    ): PluginCommand? {
        val command = plugin.getCommand(name)
        if (command == null) {
            val message = "Command '$name' is missing from plugin.yml; it will not respond."
            when (missingCommandPolicy) {
                MissingCommandPolicy.WARN -> plugin.logger.severe(message)
                MissingCommandPolicy.THROW -> throw IllegalStateException(message)
            }
            return null
        }
        command.setExecutor(executor)
        val completer = tabCompleter ?: executor as? TabCompleter
        if (completer != null) {
            command.tabCompleter = completer
        }
        return command
    }

    /**
     * Registers a runtime-built [command] under [fallbackPrefix] and returns a [Terminable] that
     * removes it again. When this registrar was built with a [TerminableConsumer] the handle is
     * bound to it, so the command disappears on plugin disable without a manual cleanup hook.
     *
     * Returns null when the command map cannot be reached or the server refused the registration.
     */
    fun registerDynamicCommand(fallbackPrefix: String, command: Command): Terminable? {
        val map = commandMap ?: CommandMaps.resolve(plugin.server, plugin.logger)
        if (map == null) {
            plugin.logger.severe(
                "Cannot register dynamic command '${command.name}': the server command map is unreachable.",
            )
            return null
        }
        if (!map.register(fallbackPrefix, command)) {
            // register() returns false when the primary label was taken and only a prefixed alias
            // was installed. The command still exists, so it must still be unregistered later.
            plugin.logger.warning(
                "Dynamic command '${command.name}' was registered under a fallback label only; " +
                    "another plugin already owns that name.",
            )
        }
        val handle = Terminable { CommandMaps.unregister(map, command, plugin.logger) }
        return terminables?.bind(handle) ?: handle
    }
}
