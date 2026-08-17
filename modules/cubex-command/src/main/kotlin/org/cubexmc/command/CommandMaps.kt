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
     * race must not delete the winner's entry. Returns the labels that were removed.
     */
    @JvmStatic
    @JvmOverloads
    fun unregister(map: CommandMap, command: Command, logger: Logger? = null): List<String> {
        val known = knownCommands(map, logger) ?: return emptyList()
        val removed = ArrayList<String>()
        val iterator = known.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value === command) {
                removed.add(entry.key)
                iterator.remove()
            }
        }
        runCatching { command.unregister(map) }
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
