package org.cubexmc.regions.service

import org.bukkit.Location
import org.bukkit.entity.Player
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionLifecycle
import org.cubexmc.regions.model.RegionTrigger

class RegionDetectionService(private val plugin: RegionsPlugin) {
    fun regionsAt(location: Location): List<RegionDefinition> =
        plugin.regions().all()
            .filter { it.enabled && it.lifecycle == RegionLifecycle.PUBLISHED }
            .filter { region ->
                val source = plugin.sources().find(region.source.type) ?: return@filter false
                source.isAvailable() && source.contains(region.source, location)
            }
            .sortedWith(compareByDescending<RegionDefinition> { it.priority }.thenBy { it.id })

    fun updatePlayer(player: Player) {
        val currentRegions = regionsAt(player.location)
        val resolution = plugin.overlaps().resolve(currentRegions)
        val primaryModeId = resolution.primaryModeRegion?.id
        val current = currentRegions.map { it.id }.toSet()
        val active = plugin.sessions().activeSessions(player.uniqueId).map { it.regionId }.toSet()

        for (regionId in active - current) {
            val region = plugin.regions().find(regionId)
            if (region != null) {
                plugin.triggers().fire(RegionTrigger.ON_LEAVE, player, region)
            }
            plugin.sessions().leave(player, regionId, "region-leave")
        }

        val entered = ArrayList<RegionDefinition>()
        for (region in currentRegions) {
            if (!active.contains(region.id)) {
                plugin.sessions().enter(player, region, modeActive = false)
                entered.add(region)
            }
        }
        plugin.sessions().reconcileModeOwner(player, currentRegions, primaryModeId)
        plugin.sessions().reconcileEffects(player, currentRegions)
        for (region in entered) {
            plugin.triggers().fire(RegionTrigger.ON_ENTER, player, region)
        }
    }

    fun updateAllOnline(refreshEffects: Boolean = false) {
        for (player in plugin.server.onlinePlayers.toList()) {
            plugin.regionScheduler().runAtEntity(player, Runnable {
                updatePlayer(player)
                if (refreshEffects) plugin.sessions().refreshPlayer(player)
            })
        }
    }
}
