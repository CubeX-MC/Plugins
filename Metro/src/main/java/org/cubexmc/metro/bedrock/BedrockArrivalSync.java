package org.cubexmc.metro.bedrock;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.cubexmc.metro.util.SchedulerUtil;

/**
 * Periodically pushes a zero-velocity update to a stopped minecart so that Bedrock
 * clients (via Geyser) stop predicting forward motion while the train waits at a
 * station.
 *
 * <p>Bedrock minecarts have no {@code maxSpeed} concept, so Geyser cannot translate
 * {@code setMaxSpeed(0)}. The Bedrock client keeps simulating the last received
 * velocity forward, which desynchronizes the mounted player and can drop them into
 * the void. {@link org.bukkit.craftbukkit Bukkit}'s {@code setVelocity} sets the
 * entity's {@code hasImpulse} flag, which forces the entity tracker to broadcast a
 * velocity packet — repeating that each tick suppresses the client-side
 * extrapolation.</p>
 */
final class BedrockArrivalSync {

    private static final long PERIOD_TICKS = 2L;
    private static final double DEPARTURE_VELOCITY_EPSILON_SQ = 1.0E-6;

    private final ConcurrentHashMap<UUID, Object> activeTasks = new ConcurrentHashMap<>();

    void start(Plugin plugin, Player passenger, Minecart minecart) {
        if (plugin == null || passenger == null || minecart == null) {
            return;
        }
        stop(minecart);

        UUID cartId = minecart.getUniqueId();
        Vector zero = new Vector(0, 0, 0);
        Object[] handleHolder = new Object[1];
        Runnable tick = () -> {
            Object current = handleHolder[0];
            if (minecart.isDead() || !minecart.isValid()) {
                cancelByHandle(cartId, current);
                return;
            }
            if (passenger.getVehicle() != minecart) {
                cancelByHandle(cartId, current);
                return;
            }
            Vector velocity = minecart.getVelocity();
            if (velocity.lengthSquared() > DEPARTURE_VELOCITY_EPSILON_SQ) {
                cancelByHandle(cartId, current);
                return;
            }
            minecart.setVelocity(zero);
        };
        Object scheduled = SchedulerUtil.entityRun(plugin, minecart, tick, PERIOD_TICKS, PERIOD_TICKS);
        handleHolder[0] = scheduled;
        if (scheduled != null) {
            activeTasks.put(cartId, scheduled);
        }
    }

    void stop(Minecart minecart) {
        if (minecart == null) {
            return;
        }
        Object handle = activeTasks.remove(minecart.getUniqueId());
        if (handle != null) {
            SchedulerUtil.cancelTask(handle);
        }
    }

    private void cancelByHandle(UUID cartId, Object handle) {
        if (handle == null) {
            return;
        }
        activeTasks.remove(cartId, handle);
        SchedulerUtil.cancelTask(handle);
    }
}
