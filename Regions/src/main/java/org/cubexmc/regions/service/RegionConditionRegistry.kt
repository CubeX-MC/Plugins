package org.cubexmc.regions.service

class RegionConditionRegistry {
    private val conditionTypes: MutableSet<String> = LinkedHashSet()

    fun register(type: String) {
        if (type.isNotBlank()) {
            conditionTypes.add(type.lowercase())
        }
    }

    fun registerDefaults() {
        listOf(
            "permission",
            "region",
            "mode",
            "chance",
            "has_union",
            "union",
            "metadata",
            "session_metadata",
        ).forEach { register(it) }
    }

    fun isRegistered(type: String): Boolean = conditionTypes.contains(type.lowercase())

    fun all(): Set<String> = conditionTypes.toSet()
}
