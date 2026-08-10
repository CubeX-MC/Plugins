package org.cubexmc.regions.service

import org.bukkit.Location
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.integration.RegionSource
import org.cubexmc.regions.integration.RegionSourceRegistry
import org.cubexmc.regions.model.ExternalRegion
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionLifecycle
import org.cubexmc.regions.model.RegionSourceRef
import org.cubexmc.regions.storage.RegionStorage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID

class RegionDetectionServiceTest {
    private val location = mock(Location::class.java)

    @Test
    fun `a source resolves the whole lookup once no matter how many regions it backs`() {
        val source = CountingSource(matching = setOf("capital:arena"))
        val regions = listOf(
            published("arena", "capital", "arena"),
            published("arena_vip", "capital", "arena"),
            published("plaza", "capital", "plaza"),
        )
        val service = service(regions, source, revision = 1L)

        val matched = service.regionsAt(location)

        assertEquals(listOf("arena", "arena_vip"), matched.map { it.id })
        // Three regions, one external resolution.
        assertEquals(1, source.containingCalls)
        assertEquals(1, source.availabilityChecks)
        assertEquals(0, source.containsCalls)
    }

    @Test
    fun `repeated lookups reuse the published set until the storage revision changes`() {
        val source = CountingSource(matching = setOf("capital:arena"))
        val storage = mock(RegionStorage::class.java)
        val registry = mock(RegionRegistry::class.java)
        val plugin = plugin(storage, registry, source)
        `when`(registry.all()).thenReturn(listOf(published("arena", "capital", "arena")))
        `when`(storage.publishedRevision()).thenReturn(1L, 1L, 2L)
        val service = RegionDetectionService(plugin)

        service.regionsAt(location)
        service.regionsAt(location)
        service.regionsAt(location)

        // Two lookups at revision 1 share one rebuild; the revision bump forces a third read.
        org.mockito.Mockito.verify(registry, org.mockito.Mockito.times(2)).all()
    }

    @Test
    fun `unavailable sources contribute nothing and are not asked to resolve`() {
        val source = CountingSource(matching = setOf("capital:arena"), available = false)
        val service = service(listOf(published("arena", "capital", "arena")), source, revision = 1L)

        assertEquals(emptyList<RegionDefinition>(), service.regionsAt(location))
        assertEquals(0, source.containingCalls)
    }

    @Test
    fun `drafts frozen and disabled regions are never detected`() {
        val source = CountingSource(matching = setOf("capital:arena"))
        val regions = listOf(
            published("live", "capital", "arena"),
            published("draft", "capital", "arena").copy(lifecycle = RegionLifecycle.DRAFT),
            published("frozen", "capital", "arena").copy(lifecycle = RegionLifecycle.FROZEN),
            published("off", "capital", "arena").copy(enabled = false),
        )
        val service = service(regions, source, revision = 1L)

        assertEquals(listOf("live"), service.regionsAt(location).map { it.id })
    }

    private fun service(regions: List<RegionDefinition>, source: RegionSource, revision: Long): RegionDetectionService {
        val storage = mock(RegionStorage::class.java)
        val registry = mock(RegionRegistry::class.java)
        `when`(storage.publishedRevision()).thenReturn(revision)
        `when`(registry.all()).thenReturn(regions)
        return RegionDetectionService(plugin(storage, registry, source))
    }

    private fun plugin(storage: RegionStorage, registry: RegionRegistry, source: RegionSource): RegionsPlugin {
        val plugin = mock(RegionsPlugin::class.java)
        `when`(plugin.storage()).thenReturn(storage)
        `when`(plugin.regions()).thenReturn(registry)
        `when`(plugin.sources()).thenReturn(RegionSourceRegistry().apply { register(source) })
        return plugin
    }

    private fun published(id: String, land: String, area: String) = RegionDefinition(
        id = id,
        name = id,
        source = RegionSourceRef("lands", mapOf("land" to land, "area" to area)),
    )

    private class CountingSource(
        private val matching: Set<String>,
        private val available: Boolean = true,
    ) : RegionSource {
        override val type: String = "lands"
        var containingCalls = 0
        var containsCalls = 0
        var availabilityChecks = 0

        override fun isAvailable(): Boolean {
            availabilityChecks++
            return available
        }

        override fun containing(refs: Collection<RegionSourceRef>, location: Location): Set<RegionSourceRef> {
            containingCalls++
            return refs.filterTo(LinkedHashSet()) { matching.contains(key(it)) }
        }

        override fun contains(ref: RegionSourceRef, location: Location): Boolean {
            containsCalls++
            return matching.contains(key(ref))
        }

        override fun resolve(ref: RegionSourceRef): ExternalRegion? = null

        override fun getOwnedRegions(playerId: UUID): List<ExternalRegion> = emptyList()

        override fun isOwner(ref: RegionSourceRef, playerId: UUID): Boolean = false

        private fun key(ref: RegionSourceRef): String = "${ref.values["land"]}:${ref.values["area"]}"
    }
}
