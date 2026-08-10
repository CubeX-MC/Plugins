package org.cubexmc.metro.service

/**
 * Simple skeleton for section occupancy. Real implementation will
 * map (lineId, fromStopId, toStopId, direction) -> occupiedByTrainId
 */
class BlockSectionManager {

    private val occupiedKeys: MutableSet<String> = HashSet()

    fun tryEnter(sectionKey: String): Boolean = occupiedKeys.add(sectionKey)

    fun leave(sectionKey: String) {
        occupiedKeys.remove(sectionKey)
    }

    fun isOccupied(sectionKey: String): Boolean = occupiedKeys.contains(sectionKey)
}
