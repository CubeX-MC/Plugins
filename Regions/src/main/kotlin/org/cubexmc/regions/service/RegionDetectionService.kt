package org.cubexmc.regions.service

import org.bukkit.Location
import org.bukkit.entity.Player
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionLifecycle
import org.cubexmc.regions.model.RegionTrigger

class RegionDetectionService(private val plugin: RegionsPlugin) {
    @Volatile
    private var candidates: PublishedCandidates = PublishedCandidates(-1L, emptyMap())

    private data class PublishedCandidates(
        val revision: Long,
        val bySourceType: Map<String, List<RegionDefinition>>,
    )

    /**
     * Called on every block-boundary move, so the work is grouped rather than per region: the
     * published set is rebuilt only when it actually changes, source availability is evaluated once
     * per source, and each source resolves the whole location lookup in a single call.
     */
    fun regionsAt(location: Location): List<RegionDefinition> {
        val grouped = publishedCandidates().bySourceType
        if (grouped.isEmpty()) return emptyList()
        val matched = ArrayList<RegionDefinition>()
        for ((sourceType, regions) in grouped) {
            val source = plugin.sources().find(sourceType) ?: continue
            if (!source.isAvailable()) continue
            val hits = source.containing(regions.map { it.source }.distinct(), location)
            if (hits.isEmpty()) continue
            regions.filterTo(matched) { hits.contains(it.source) }
        }
        return matched.sortedWith(RegionOverlapResolver.REGION_ORDER)
    }

    private fun publishedCandidates(): PublishedCandidates {
        val revision = plugin.storage().publishedRevision()
        val cached = candidates
        if (cached.revision == revision) return cached
        val rebuilt = PublishedCandidates(
            revision,
            plugin.regions().all()
                .filter { it.enabled && it.lifecycle == RegionLifecycle.PUBLISHED }
                .groupBy { it.source.type },
        )
        candidates = rebuilt
        return rebuilt
    }

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
