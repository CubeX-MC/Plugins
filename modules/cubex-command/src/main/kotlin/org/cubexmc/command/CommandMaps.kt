package org.cubexmc.command

import java.util.logging.Level
import java.util.logging.Logger
import org.bukkit.Bukkit
import org.bukkit.Server
import org.bukkit.command.Command
import org.bukkit.command.CommandMap
import org.bukkit.command.SimpleCommandMap

/**
 * Resolves the server's [CommandMap] across the shapes CubeX plugins actually meet.
 *
 * Paper exposes `Bukkit.getCommandMap()`; older Spigot builds only have a private `commandMap`
 * field on the CraftServer. RuleGems and FAWEReplacer each carried their own copy of this dance.
 */
object CommandMaps {
    @JvmStatic
    @JvmOverloads
    fun resolve(server: Server, logger: Logger? = null): CommandMap? {
        try {
            val viaBukkit = Bukkit::class.java.getMethod("getCommandMap").invoke(null)
            if (viaBukkit is CommandMap) return viaBukkit
        } catch (_: NoSuchMethodException) {
            // Pre-Paper server: fall through to the reflection path.
        } catch (exception: Exception) {
            logger?.fine("Bukkit.getCommandMap() failed: ${exception.message}")
        }

        return try {
            val field = server.javaClass.getDeclaredField("commandMap")
            field.isAccessible = true
            field.get(server) as? CommandMap
        } catch (exception: ReflectiveOperationException) {
            logger?.log(Level.SEVERE, "Unable to access the Bukkit command map via reflection", exception)
            null
        } catch (exception: SecurityException) {
            logger?.log(Level.SEVERE, "Unable to access the Bukkit command map via reflection", exception)
            null
        }
    }

    /**
     * Removes [command] from [map], including its aliases, and unregisters it.
     *
     * Bukkit has no public "unregister one command" call, so the `knownCommands` map is edited
     * directly. Only entries actually pointing at [command] are removed — a plugin that lost a name
     * race must not delete the winner's entry. Returns the labels that were **actually** removed.
     *
     * [Command.unregister] always runs, even when the map refuses edits: it is the supported API and
     * on newer servers it is what actually detaches the command.
     */
    @JvmStatic
    @JvmOverloads
    fun unregister(map: CommandMap, command: Command, logger: Logger? = null): List<String> {
        val known = knownCommands(map, logger)
        val removed = if (known == null) emptyList() else removeMatching(known, command, logger)
        runCatching { command.unregister(map) }
        return removed
    }

    /**
     * Drops every entry of [known] that points at [command] **by identity**, returning the labels
     * that were really removed.
     *
     * Two things this must survive, both seen in the wild:
     *
     * 1. **The map refuses edits.** Paper 26.x hands back a `knownCommands` whose entry-set iterator
     *    does not implement `remove`, so the old mutate-while-iterating loop blew up with
     *    `UnsupportedOperationException` and took `/rg reload` down with it. Matching labels are
     *    therefore collected first and removed afterwards, each attempt guarded on its own.
     * 2. **Removal silently does nothing.** If the map is a copy rather than the live one, the
     *    entries stay put; the label is then *not* reported as removed, so callers never believe a
     *    cleanup happened that did not.
     */
    @JvmStatic
    internal fun removeMatching(
        known: MutableMap<String, Command>,
        command: Command,
        logger: Logger? = null,
    ): List<String> {
        val labels = known.entries.filter { it.value === command }.map { it.key }
        if (labels.isEmpty()) return emptyList()

        val removed = ArrayList<String>(labels.size)
        var refused = false
        for (label in labels) {
            val outcome = runCatching { known.remove(label) }
            if (outcome.isFailure) {
                refused = true
                continue
            }
            if (!known.containsKey(label)) {
                removed.add(label)
            }
        }
        if (refused || removed.size < labels.size) {
            logger?.log(
                Level.WARNING,
                "knownCommands refused removal of ${labels - removed.toSet()}; " +
                    "relying on Command.unregister instead",
            )
        }
        return removed
    }

    /**
     * The live `knownCommands` map behind a [SimpleCommandMap], or null when it cannot be reached.
     *
     * Editing it directly is the only way to override a label another plugin already owns, which is
     * what RuleGems' allowed-command proxies deliberately do. Prefer [unregister] for removal.
     */
    @JvmStatic
    @JvmOverloads
    @Suppress("UNCHECKED_CAST")
    fun knownCommands(map: CommandMap, logger: Logger? = null): MutableMap<String, Command>? =
        try {
            val field = SimpleCommandMap::class.java.getDeclaredField("knownCommands")
            field.isAccessible = true
            field.get(map) as? MutableMap<String, Command>
        } catch (exception: ReflectiveOperationException) {
            logger?.log(Level.WARNING, "Unable to reach SimpleCommandMap.knownCommands", exception)
            null
        } catch (exception: SecurityException) {
            logger?.log(Level.WARNING, "Unable to reach SimpleCommandMap.knownCommands", exception)
            null
        }
}
