package org.cubexmc.regions.integration

class RegionSourceRegistry {
    private val sources: MutableMap<String, RegionSource> = LinkedHashMap()

    fun register(source: RegionSource) {
        sources[source.type.lowercase()] = source
    }

    fun find(type: String): RegionSource? = sources[type.lowercase()]

    fun all(): Collection<RegionSource> = sources.values.toList()
}
