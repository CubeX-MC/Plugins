package org.cubexmc.regions.service

import org.bukkit.entity.Player
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.model.FlagConfig
import org.cubexmc.regions.model.RegionDefinition
import java.util.Locale

class RegionFlagService(private val plugin: RegionsPlugin) {
    fun isDenied(player: Player, key: String): Boolean =
        !player.hasPermission("regions.bypass.flags") &&
            resolveFlag(player, key)?.value?.equals("deny", ignoreCase = true) == true

    fun isAllowed(player: Player, key: String): Boolean =
        player.hasPermission("regions.bypass.flags") ||
            resolveFlag(player, key)?.value?.equals("allow", ignoreCase = true) == true

    fun isCommandBlocked(player: Player, rootLabel: String): Boolean {
        if (player.hasPermission("regions.bypass.flags")) return false
        val flag = resolveFlag(player, "commands") ?: return false
        val mode = flag.values["mode"]?.lowercase(Locale.ROOT) ?: flag.value.lowercase(Locale.ROOT)
        val values = splitList(flag.values["values"] ?: flag.values["commands"] ?: "")
        val label = rootLabel.lowercase(Locale.ROOT)
        return when (mode) {
            "blocklist", "deny" -> values.any { it == label }
            "allowlist", "allow" -> values.isNotEmpty() && values.none { it == label }
            else -> false
        }
    }

    private fun resolveFlag(player: Player, key: String): FlagConfig? {
        plugin.trials().activeDraft(player.uniqueId)
            ?.flags
            ?.entries
            ?.firstOrNull { it.key.equals(key, ignoreCase = true) }
            ?.value
            ?.takeUnless { it.value.equals("pass", ignoreCase = true) }
            ?.let { return it }
        return plugin.overlaps().resolve(activeRegions(player))
            .flags[key.lowercase(Locale.ROOT)]
            ?.config
    }

    private fun activeRegions(player: Player): List<RegionDefinition> =
        plugin.sessions().activeSessions(player.uniqueId)
            .mapNotNull { plugin.regions().find(it.regionId) }
            .sortedWith(RegionOverlapResolver.REGION_ORDER)

    private fun splitList(value: String): Set<String> =
        value
            .removePrefix("[")
            .removeSuffix("]")
            .split(',', ';', ' ')
            .map { it.trim().removePrefix("/").lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .toSet()
}
