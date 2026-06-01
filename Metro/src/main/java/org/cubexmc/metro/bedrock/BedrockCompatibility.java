package org.cubexmc.metro.bedrock;

import java.util.concurrent.CompletableFuture;

import org.bukkit.Location;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Single entry point for Bedrock-aware behavior.
 *
 * <p>All Bedrock workarounds live behind this facade so that business code can stay
 * agnostic of Geyser/Floodgate quirks. Java players take the lightweight code path;
 * Bedrock players get conservative timings and any future targeted fixes.</p>
 */
public final class BedrockCompatibility {

    private final Plugin plugin;
    private final BedrockArrivalSync arrivalSync;

    public BedrockCompatibility(Plugin plugin) {
        this.plugin = plugin;
        this.arrivalSync = new BedrockArrivalSync();
    }

    /**
     * Returns true if the player is connected through Geyser/Floodgate.
     */
    public boolean isBedrock(Player player) {
        return BedrockDetector.isBedrockPlayer(player);
    }

    /**
     * Teleports a player to the given destination, dismounting any vehicle first and
     * applying conservative delays for Bedrock clients.
     */
    public CompletableFuture<Boolean> teleportPlayer(Player player, Location destination) {
        return BedrockMountSync.teleportPlayer(plugin, player, destination);
    }

    /**
     * Teleports a passenger and remounts them on the supplied minecart. Used by portal
     * flows that need to move a riding player across worlds.
     */
    public CompletableFuture<Boolean> teleportAndMountPassenger(Player passenger, Location destination,
            Minecart targetCart) {
        return BedrockMountSync.teleportAndMountPassenger(plugin, passenger, destination, targetCart);
    }

    /**
     * Hooks into train arrival. For Bedrock passengers schedules a periodic
     * zero-velocity update so the Bedrock client stops predicting forward motion
     * and the mounted player does not drift into the void. No-op for Java players.
     */
    public void onTrainArrival(Player passenger, Minecart minecart) {
        // 临时测试：禁用 BedrockArrivalSync，验证到站漂移是否已由 teleport 修复解决。
        // 若基岩玩家到站后仍漂移/掉虚空，则恢复以下代码；否则可整体移除该特性。
        // if (passenger == null || minecart == null || !isBedrock(passenger)) {
        //     return;
        // }
        // arrivalSync.start(plugin, passenger, minecart);
    }

    /**
     * Cancels any active arrival sync for this minecart. Safe to call for Java
     * passengers or carts that never had a sync running.
     */
    public void onTrainDeparture(Minecart minecart) {
        arrivalSync.stop(minecart);
    }
}
