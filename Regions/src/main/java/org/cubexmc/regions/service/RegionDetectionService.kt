package org.cubexmc.regions.service

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionTrigger

class RegionDetectionService(private val plugin: RegionsPlugin) {
    fun regionsAt(location: Location): List<RegionDefinition> =
        plugin.regions().all()
            .filter { it.enabled }
            .filter { region ->
                val source = plugin.sources().find(region.source.type) ?: return@filter false
                source.isAvailable() && source.contains(region.source, location)
            }
            .sortedWith(compareByDescending<RegionDefinition> { it.priority }.thenBy { it.id })

    fun updatePlayer(player: Player) {
        val current = regionsAt(player.location).map { it.id }.toSet()
        val active = plugin.sessions().activeSessions(player.uniqueId).map { it.regionId }.toSet()

        for (regionId in active - current) {
            val region = plugin.regions().find(regionId)
            if (region != null) {
                plugin.triggers().fire(RegionTrigger.ON_LEAVE, player, region)
            }
            plugin.sessions().leave(player, regionId, "region-leave")
        }

        for (region in regionsAt(player.location)) {
            if (!active.contains(region.id)) {
                plugin.sessions().enter(player, region)
                plugin.triggers().fire(RegionTrigger.ON_ENTER, player, region)
            }
        }
    }

    fun updateAllOnline() {
        for (player in Bukkit.getOnlinePlayers().toList()) {
            plugin.regionScheduler().runAtEntity(player, Runnable {
                updatePlayer(player)
                plugin.sessions().refreshPlayer(player)
            })
        }
    }
}
