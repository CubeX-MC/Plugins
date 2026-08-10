package org.cubexmc.metro.train

import java.util.UUID
import org.bukkit.entity.HumanEntity
import org.bukkit.entity.Minecart

internal class TrainPassengerRegistry {
    private val passengers = HashMap<UUID, PassengerRecord>()

    fun add(player: HumanEntity?, cart: Minecart?): Boolean {
        if (player != null) {
            return passengers.put(player.uniqueId, PassengerRecord(player, cart)) == null
        }
        return false
    }

    fun remove(player: HumanEntity?) {
        if (player != null) {
            passengers.remove(player.uniqueId)
        }
    }

    fun contains(player: HumanEntity?): Boolean = player != null && passengers.containsKey(player.uniqueId)

    fun contains(playerId: UUID?): Boolean = playerId != null && passengers.containsKey(playerId)

    fun onlinePassengers(): List<HumanEntity> =
        passengers.values
            .filter { isPlayerOnline(it.player) }
            .map { it.player }

    fun hasOnlinePassengers(): Boolean = passengers.values.any { isPlayerOnline(it.player) }

    fun cartFor(player: HumanEntity?): Minecart? {
        if (player == null) {
            return null
        }
        return passengers[player.uniqueId]?.cart
    }

    fun snapshotPassengerIds(): Set<UUID> = HashSet(passengers.keys)

    fun clear() {
        passengers.clear()
    }

    private fun isPlayerOnline(entity: HumanEntity): Boolean =
        try {
            entity.javaClass.getMethod("isOnline").invoke(entity) as Boolean
        } catch (_: Exception) {
            false
        }

    private class PassengerRecord(
        val player: HumanEntity,
        val cart: Minecart?,
    )
}
