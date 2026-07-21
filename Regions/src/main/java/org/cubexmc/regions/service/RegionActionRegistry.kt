package org.cubexmc.regions.service

class RegionActionRegistry {
    private val actionTypes: MutableSet<String> = LinkedHashSet()

    fun register(type: String) {
        if (type.isNotBlank()) {
            actionTypes.add(type.lowercase())
        }
    }

    fun registerDefaults() {
        listOf(
            "message",
            "title",
            "sound",
            "console_command",
            "player_command",
            "effect_apply",
            "effect_clear",
            "broadcast",
            "teleport",
            "heal",
            "feed",
            "extinguish",
            "give_item",
            "take_item",
            "set_metadata",
            "clear_metadata",
            "cleanup_region",
            "mode_command",
        ).forEach { register(it) }
    }

    fun isRegistered(type: String): Boolean = actionTypes.contains(type.lowercase())

    fun all(): Set<String> = actionTypes.toSet()
}
