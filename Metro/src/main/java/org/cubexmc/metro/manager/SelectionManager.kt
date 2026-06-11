package org.cubexmc.metro.manager

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.Location
import org.bukkit.entity.Player

class SelectionManager {
    private val corner1Selections: MutableMap<UUID, Location> = ConcurrentHashMap()
    private val corner2Selections: MutableMap<UUID, Location> = ConcurrentHashMap()

    fun setCorner1(player: Player, location: Location) {
        corner1Selections[player.uniqueId] = location
    }

    fun setCorner2(player: Player, location: Location) {
        corner2Selections[player.uniqueId] = location
    }

    fun getCorner1(player: Player): Location? = corner1Selections[player.uniqueId]

    fun getCorner2(player: Player): Location? = corner2Selections[player.uniqueId]

    fun isSelectionComplete(player: Player): Boolean =
        corner1Selections.containsKey(player.uniqueId) && corner2Selections.containsKey(player.uniqueId)
}
