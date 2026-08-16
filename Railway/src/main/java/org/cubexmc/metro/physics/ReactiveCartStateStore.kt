package org.cubexmc.metro.physics

import java.util.UUID
import org.bukkit.Location
import org.bukkit.util.Vector

internal class ReactiveCartStateStore {

    private val states = HashMap<UUID, CartState>()

    fun clear() {
        states.clear()
    }

    fun retainAll(validIds: Set<UUID>) {
        states.keys.retainAll(validIds)
    }

    fun rememberVelocity(cartId: UUID?, velocity: Vector?) {
        if (cartId == null || velocity == null) {
            return
        }
        stateFor(cartId).commandedVelocity = velocity.clone()
    }

    fun rememberDirection(cartId: UUID?, direction: Vector?) {
        if (cartId == null || direction == null || direction.lengthSquared() < 1.0e-8) {
            return
        }
        stateFor(cartId).lastDirection = direction.clone().normalize()
    }

    fun rememberPosition(cartId: UUID?, position: Location?) {
        if (cartId == null || position == null) {
            return
        }
        stateFor(cartId).commandedPosition = position.clone()
    }

    fun getVelocity(cartId: UUID?): Vector? = states[cartId]?.commandedVelocity?.clone()

    fun getDirection(cartId: UUID?): Vector? = states[cartId]?.lastDirection?.clone()

    fun getPosition(cartId: UUID?): Location? = states[cartId]?.commandedPosition?.clone()

    fun size(): Int = states.size

    private fun stateFor(cartId: UUID): CartState = states.computeIfAbsent(cartId) { CartState() }

    private class CartState {
        var commandedVelocity: Vector? = null
        var lastDirection: Vector? = null
        var commandedPosition: Location? = null
    }
}
