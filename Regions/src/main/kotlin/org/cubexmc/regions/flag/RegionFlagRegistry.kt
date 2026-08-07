package org.cubexmc.regions.flag

class RegionFlagRegistry {
    private val flagKeys: MutableSet<String> = LinkedHashSet()

    fun register(key: String) {
        if (key.isNotBlank()) {
            flagKeys.add(key.lowercase())
        }
    }

    fun registerDefaults() {
        listOf(
            "pvp",
            "fly",
            "vanish",
            "item_drop",
            "item_pickup",
            "commands",
        ).forEach { register(it) }
    }

    fun isRegistered(key: String): Boolean = flagKeys.contains(key.lowercase())

    fun all(): Set<String> = flagKeys.toSet()
}
