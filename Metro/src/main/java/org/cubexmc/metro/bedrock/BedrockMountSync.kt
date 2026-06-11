package org.cubexmc.metro.bedrock

import java.util.concurrent.CompletableFuture
import org.bukkit.Location
import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.cubexmc.metro.util.SchedulerUtil

/**
 * Mount-aware teleport flows. Uses conservative dismount/remount delays for Bedrock
 * passengers to work around Geyser mount synchronization timing.
 */
object BedrockMountSync {
    fun teleportPlayer(plugin: Plugin?, player: Player?, destination: Location?): CompletableFuture<Boolean> {
        if (plugin == null || player == null || destination == null || !player.isOnline) {
            return CompletableFuture.completedFuture(false)
        }

        val vehicle = player.vehicle
        if (vehicle == null) {
            return SchedulerUtil.teleportEntity(player, destination)
        }

        vehicle.removePassenger(player)
        val result = CompletableFuture<Boolean>()
        SchedulerUtil.entityRun(
            plugin,
            player,
            Runnable { SchedulerUtil.teleportEntity(player, destination).thenAccept { success -> result.complete(success) } },
            mountedTeleportDelay(player),
            -1L,
        )
        return result
    }

    fun teleportAndMountPassenger(
        plugin: Plugin?,
        passenger: Player?,
        destination: Location?,
        targetCart: Minecart?,
    ): CompletableFuture<Boolean> {
        if (plugin == null || passenger == null || destination == null || targetCart == null || !passenger.isOnline) {
            return CompletableFuture.completedFuture(false)
        }

        val vehicle = passenger.vehicle
        if (vehicle != null) {
            vehicle.removePassenger(passenger)
        }

        val result = CompletableFuture<Boolean>()
        SchedulerUtil.entityRun(
            plugin,
            passenger,
            Runnable {
                if (!passenger.isOnline) {
                    result.complete(false)
                    return@Runnable
                }

                SchedulerUtil.teleportEntity(passenger, destination).thenAccept { success ->
                    if (!success) {
                        result.complete(false)
                        return@thenAccept
                    }

                    SchedulerUtil.regionRun(
                        plugin,
                        destination,
                        Runnable {
                            if (!passenger.isOnline || !targetCart.isValid) {
                                result.complete(false)
                                return@Runnable
                            }
                            result.complete(targetCart.addPassenger(passenger))
                        },
                        remountDelay(passenger),
                        -1L,
                    )
                }
            },
            mountedTeleportDelay(passenger),
            -1L,
        )
        return result
    }

    private fun mountedTeleportDelay(player: Player): Long =
        if (BedrockDetector.isBedrockPlayer(player)) {
            BedrockTimings.BEDROCK_MOUNTED_TELEPORT_DELAY_TICKS
        } else {
            BedrockTimings.JAVA_MOUNTED_TELEPORT_DELAY_TICKS
        }

    private fun remountDelay(player: Player): Long =
        if (BedrockDetector.isBedrockPlayer(player)) {
            BedrockTimings.BEDROCK_REMOUNT_DELAY_TICKS
        } else {
            BedrockTimings.JAVA_REMOUNT_DELAY_TICKS
        }
}
