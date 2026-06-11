package org.cubexmc.metro.bedrock

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import org.cubexmc.metro.util.SchedulerUtil

/**
 * Periodically pushes a zero-velocity update to a stopped minecart so that Bedrock
 * clients (via Geyser) stop predicting forward motion while the train waits at a
 * station.
 *
 * Bedrock minecarts have no `maxSpeed` concept, so Geyser cannot translate
 * `setMaxSpeed(0)`. The Bedrock client keeps simulating the last received
 * velocity forward, which desynchronizes the mounted player and can drop them into
 * the void. Bukkit's `setVelocity` sets the entity's `hasImpulse` flag, which
 * forces the entity tracker to broadcast a velocity packet; repeating that each
 * tick suppresses the client-side extrapolation.
 */
class BedrockArrivalSync {
    private val activeTasks = ConcurrentHashMap<UUID, Any>()

    fun start(plugin: Plugin?, passenger: Player?, minecart: Minecart?) {
        if (plugin == null || passenger == null || minecart == null) {
            return
        }
        stop(minecart)

        val cartId = minecart.uniqueId
        val zero = Vector(0, 0, 0)
        val handleHolder = arrayOfNulls<Any>(1)
        val tick = Runnable {
            val current = handleHolder[0]
            if (minecart.isDead || !minecart.isValid) {
                cancelByHandle(cartId, current)
                return@Runnable
            }
            if (passenger.vehicle != minecart) {
                cancelByHandle(cartId, current)
                return@Runnable
            }
            val velocity = minecart.velocity
            if (velocity.lengthSquared() > DEPARTURE_VELOCITY_EPSILON_SQ) {
                cancelByHandle(cartId, current)
                return@Runnable
            }
            minecart.velocity = zero
        }
        val scheduled = SchedulerUtil.entityRun(plugin, minecart, tick, PERIOD_TICKS, PERIOD_TICKS)
        handleHolder[0] = scheduled
        if (scheduled != null) {
            activeTasks[cartId] = scheduled
        }
    }

    fun stop(minecart: Minecart?) {
        if (minecart == null) {
            return
        }
        val handle = activeTasks.remove(minecart.uniqueId)
        if (handle != null) {
            SchedulerUtil.cancelTask(handle)
        }
    }

    private fun cancelByHandle(cartId: UUID, handle: Any?) {
        if (handle == null) {
            return
        }
        activeTasks.remove(cartId, handle)
        SchedulerUtil.cancelTask(handle)
    }

    companion object {
        private const val PERIOD_TICKS = 2L
        private const val DEPARTURE_VELOCITY_EPSILON_SQ = 1.0E-6
    }
}
