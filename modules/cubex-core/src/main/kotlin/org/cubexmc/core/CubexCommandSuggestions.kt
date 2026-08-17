package org.cubexmc.core

import java.util.Locale

/** Shared, platform-neutral guards for Bukkit- and Paper-style command completion callbacks. */
object CubexCommandSuggestions {
    /**
     * Completes the root argument when the platform supplies zero or one argument.
     *
     * Paper's [BasicCommand][io.papermc.paper.command.brigadier.BasicCommand] may supply an empty
     * argument array, while Bukkit normally represents the same cursor position as one empty
     * argument. This method deliberately has no Paper dependency and normalizes both shapes.
     * It returns `null` once deeper arguments are present so the caller can continue routing.
     */
    @JvmStatic
    fun root(arguments: Array<out String>, candidates: Iterable<String>): List<String>? {
        if (arguments.size > 1) return null
        return matching(candidates, arguments.firstOrNull().orEmpty())
    }

    @JvmStatic
    fun matching(candidates: Iterable<String>, prefix: String): List<String> {
        val normalizedPrefix = prefix.lowercase(Locale.ROOT)
        return candidates.filter { it.lowercase(Locale.ROOT).startsWith(normalizedPrefix) }
    }
}
