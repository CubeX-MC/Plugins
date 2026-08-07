package org.cubexmc.regions.integration

import org.bukkit.Location
import org.cubexmc.regions.model.ExternalRegion
import org.cubexmc.regions.model.RegionGeometry
import org.cubexmc.regions.model.RegionSourceRef
import java.util.UUID

interface RegionSource {
    val type: String
    fun isAvailable(): Boolean
    fun resolve(ref: RegionSourceRef): ExternalRegion?
    fun contains(ref: RegionSourceRef, location: Location): Boolean

    /**
     * Returns the subset of [refs] that contain [location].
     *
     * Detection asks this once per source per lookup instead of calling [contains] per region, so a
     * source that has to resolve the location through an external API can do that work once for the
     * whole set. The default is the naive per-ref loop.
     */
    fun containing(refs: Collection<RegionSourceRef>, location: Location): Set<RegionSourceRef> =
        refs.filterTo(LinkedHashSet()) { contains(it, location) }
    fun getOwnedRegions(playerId: UUID): List<ExternalRegion>
    fun geometry(ref: RegionSourceRef): RegionGeometry? = null
    fun ownerId(ref: RegionSourceRef): UUID? = null
    fun isOwner(ref: RegionSourceRef, playerId: UUID): Boolean
}
