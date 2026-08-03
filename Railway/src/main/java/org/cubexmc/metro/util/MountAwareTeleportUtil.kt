package org.cubexmc.metro.util

import org.bukkit.Location
import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture

/**
 * Centralizes player teleport flows that may interact with vehicle mount state.
 */
object MountAwareTeleportUtil {

    private const val JAVA_MOUNTED_TELEPORT_DELAY_TICKS = 1L
    private const val JAVA_REMOUNT_DELAY_TICKS = 2L
    private const val BEDROCK_MOUNTED_TELEPORT_DELAY_TICKS = 5L
    private const val BEDROCK_REMOUNT_DELAY_TICKS = 8L

    @JvmStatic
    fun teleportPlayer(plugin: Plugin?, player: Player?, destination: Location?): CompletableFuture<Boolean> {
        if (plugin == null || player == null || destination == null || !player.isOnline) {
            return CompletableFuture.completedFuture(false)
        }

        val vehicle = player.vehicle
            ?: return SchedulerUtil.teleportEntity(player, destination)

        vehicle.removePassenger(player)
        val result = CompletableFuture<Boolean>()
        SchedulerUtil.entityRun(
            plugin,
            player,
            { SchedulerUtil.teleportEntity(player, destination).thenAccept { success -> result.complete(success) } },
            mountedTeleportDelay(player),
            -1L,
        )
        return result
    }

    @JvmStatic
    fun teleportAndMountPassenger(
        plugin: Plugin?,
        passenger: Player?,
        destination: Location?,
        targetCart: Minecart?,
    ): CompletableFuture<Boolean> {
        if (plugin == null || passenger == null || destination == null || targetCart == null ||
            !passenger.isOnline
        ) {
            return CompletableFuture.completedFuture(false)
        }

        passenger.vehicle?.removePassenger(passenger)

        val result = CompletableFuture<Boolean>()
        SchedulerUtil.entityRun(
            plugin,
            passenger,
            { teleportThenMount(plugin, passenger, destination, targetCart, result) },
            mountedTeleportDelay(passenger),
            -1L,
        )
        return result
    }

    private fun teleportThenMount(
        plugin: Plugin,
        passenger: Player,
        destination: Location,
        targetCart: Minecart,
        result: CompletableFuture<Boolean>,
    ) {
        if (!passenger.isOnline) {
            result.complete(false)
            return
        }

        SchedulerUtil.teleportEntity(passenger, destination).thenAccept { success ->
            if (!success) {
                result.complete(false)
                return@thenAccept
            }

            SchedulerUtil.regionRun(
                plugin,
                destination,
                {
                    if (!passenger.isOnline || !targetCart.isValid) {
                        result.complete(false)
                    } else {
                        result.complete(targetCart.addPassenger(passenger))
                    }
                },
                remountDelay(passenger),
                -1L,
            )
        }
    }

    private fun mountedTeleportDelay(player: Player): Long =
        if (BedrockPlayerUtil.isBedrockPlayer(player)) {
            BEDROCK_MOUNTED_TELEPORT_DELAY_TICKS
        } else {
            JAVA_MOUNTED_TELEPORT_DELAY_TICKS
        }

    private fun remountDelay(player: Player): Long =
        if (BedrockPlayerUtil.isBedrockPlayer(player)) {
            BEDROCK_REMOUNT_DELAY_TICKS
        } else {
            JAVA_REMOUNT_DELAY_TICKS
        }
}
