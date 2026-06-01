package org.cubexmc.metro.bedrock;

import java.util.concurrent.CompletableFuture;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.cubexmc.metro.util.SchedulerUtil;

/**
 * Mount-aware teleport flows. Uses conservative dismount/remount delays for Bedrock
 * passengers to work around Geyser mount synchronization timing.
 */
final class BedrockMountSync {

    private BedrockMountSync() {
    }

    static CompletableFuture<Boolean> teleportPlayer(Plugin plugin, Player player, Location destination) {
        if (plugin == null || player == null || destination == null || !player.isOnline()) {
            return CompletableFuture.completedFuture(false);
        }

        Entity vehicle = player.getVehicle();
        if (vehicle == null) {
            return SchedulerUtil.teleportEntity(player, destination);
        }

        vehicle.removePassenger(player);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        SchedulerUtil.entityRun(plugin, player,
                () -> SchedulerUtil.teleportEntity(player, destination).thenAccept(result::complete),
                mountedTeleportDelay(player), -1L);
        return result;
    }

    static CompletableFuture<Boolean> teleportAndMountPassenger(Plugin plugin, Player passenger,
            Location destination, Minecart targetCart) {
        if (plugin == null || passenger == null || destination == null || targetCart == null
                || !passenger.isOnline()) {
            return CompletableFuture.completedFuture(false);
        }

        Entity vehicle = passenger.getVehicle();
        if (vehicle != null) {
            vehicle.removePassenger(passenger);
        }

        CompletableFuture<Boolean> result = new CompletableFuture<>();
        SchedulerUtil.entityRun(plugin, passenger, () -> {
            if (!passenger.isOnline()) {
                result.complete(false);
                return;
            }

            SchedulerUtil.teleportEntity(passenger, destination).thenAccept(success -> {
                if (!success) {
                    result.complete(false);
                    return;
                }

                SchedulerUtil.regionRun(plugin, destination, () -> {
                    if (!passenger.isOnline() || !targetCart.isValid()) {
                        result.complete(false);
                        return;
                    }
                    result.complete(targetCart.addPassenger(passenger));
                }, remountDelay(passenger), -1L);
            });
        }, mountedTeleportDelay(passenger), -1L);
        return result;
    }

    private static long mountedTeleportDelay(Player player) {
        return BedrockDetector.isBedrockPlayer(player)
                ? BedrockTimings.BEDROCK_MOUNTED_TELEPORT_DELAY_TICKS
                : BedrockTimings.JAVA_MOUNTED_TELEPORT_DELAY_TICKS;
    }

    private static long remountDelay(Player player) {
        return BedrockDetector.isBedrockPlayer(player)
                ? BedrockTimings.BEDROCK_REMOUNT_DELAY_TICKS
                : BedrockTimings.JAVA_REMOUNT_DELAY_TICKS;
    }
}
