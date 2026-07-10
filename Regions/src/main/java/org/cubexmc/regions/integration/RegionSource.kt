package org.cubexmc.regions.integration

import org.bukkit.Location
import org.cubexmc.regions.model.ExternalRegion
import org.cubexmc.regions.model.RegionSourceRef
import java.util.UUID

interface RegionSource {
    val type: String
    fun isAvailable(): Boolean
    fun resolve(ref: RegionSourceRef): ExternalRegion?
    fun contains(ref: RegionSourceRef, location: Location): Boolean
    fun getOwnedRegions(playerId: UUID): List<ExternalRegion>
}
