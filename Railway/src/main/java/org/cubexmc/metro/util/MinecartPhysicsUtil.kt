package org.cubexmc.metro.util

import org.bukkit.entity.Minecart
import org.bukkit.util.Vector
import org.cubexmc.metro.Metro

/**
 * Helpers for applying consistent minecart physics overrides regardless of passengers.
 */
object MinecartPhysicsUtil {

    /**
     * Forces the minecart to adopt the supplied velocity and optionally re-applies it on
     * subsequent ticks. Disabling the follow-up scheduling is useful when the velocity is
     * enforced after vanilla physics has already run (e.g., `VehicleUpdateEvent`).
     */
    @JvmStatic
    @JvmOverloads
    fun forceVelocity(cart: Minecart?, velocity: Vector?, plugin: Metro?, scheduleFollowup: Boolean = true) {
        if (cart == null || cart.isDead || plugin == null) {
            return
        }
        val vel = velocity?.clone() ?: Vector()
        cart.setGravity(false)
        cart.isSlowWhenEmpty = false

        // Override fly speed for passengers to prevent input from affecting velocity
        cart.flyingVelocityMod = Vector(0, 0, 0)

        if (SchedulerUtil.isFolia()) {
            if (!scheduleFollowup) {
                cart.velocity = vel
                return
            }

            val velCopy = vel.clone()
            SchedulerUtil.entityRun(plugin, cart, { reapply(cart, velCopy) }, 0L, -1L)
            SchedulerUtil.entityRun(plugin, cart, { cart.velocity = velCopy }, 1L, -1L)
            SchedulerUtil.entityRun(plugin, cart, { cart.velocity = velCopy }, 2L, -1L)
            SchedulerUtil.entityRun(plugin, cart, { cart.velocity = velCopy }, 3L, -1L)
        } else {
            cart.velocity = vel
            cart.velocity = vel
            cart.velocity = vel
            if (scheduleFollowup) {
                val velCopy = vel.clone()
                SchedulerUtil.entityRun(plugin, cart, { reapply(cart, velCopy) }, 0L, -1L)
                SchedulerUtil.entityRun(plugin, cart, { cart.velocity = velCopy }, 1L, -1L)
                SchedulerUtil.entityRun(plugin, cart, { cart.velocity = velCopy }, 2L, -1L)
            }
        }
    }

    private fun reapply(cart: Minecart, velocity: Vector) {
        cart.setGravity(false)
        cart.isSlowWhenEmpty = false
        cart.flyingVelocityMod = Vector(0, 0, 0)
        cart.velocity = velocity
    }
}
