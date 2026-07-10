package org.cubexmc.regions.mode

class RegionModeRegistry {
    private val modeTypes: MutableSet<String> = LinkedHashSet()

    fun register(type: String) {
        if (type.isNotBlank()) {
            modeTypes.add(type.lowercase())
        }
    }

    fun isRegistered(type: String): Boolean = modeTypes.contains(type.lowercase())

    fun all(): Set<String> = modeTypes.toSet()
}
