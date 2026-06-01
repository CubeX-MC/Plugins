package org.cubexmc.metro.util;

import org.bukkit.entity.Minecart;
import org.bukkit.util.Vector;
import org.cubexmc.metro.Metro;

/**
 * Helpers for applying consistent minecart physics overrides regardless of passengers.
 */
public final class MinecartPhysicsUtil {

    private MinecartPhysicsUtil() {
    }

    /**
     * Forces the minecart to adopt the supplied velocity and keeps reapplying it
     * so vanilla passenger mass adjustments cannot desynchronise the consist.
     */
    public static void forceVelocity(Minecart cart, Vector velocity, Metro plugin) {
        forceVelocity(cart, velocity, plugin, true);
    }

    /**
     * Forces the minecart to adopt the supplied velocity and optionally re-applies it on
     * subsequent ticks. Disabling the follow-up scheduling is useful when the velocity is
     * enforced after vanilla physics has already run (e.g., {@link org.bukkit.event.vehicle.VehicleUpdateEvent}).
     */
    public static void forceVelocity(Minecart cart, Vector velocity, Metro plugin, boolean scheduleFollowup) {
        if (cart == null || cart.isDead() || plugin == null) {
            return;
        }
        Vector vel = velocity == null ? new Vector() : velocity.clone();
        cart.setGravity(false);
        cart.setSlowWhenEmpty(false);
        
        // Override fly speed for passengers to prevent input from affecting velocity
        cart.setFlyingVelocityMod(new Vector(0, 0, 0));

        if (SchedulerUtil.isFolia()) {
            if (!scheduleFollowup) {
                cart.setVelocity(vel);
                return;
            }

            final Vector velCopy = vel.clone();
            SchedulerUtil.entityRun(plugin, cart, () -> {
                cart.setGravity(false);
                cart.setSlowWhenEmpty(false);
                cart.setFlyingVelocityMod(new Vector(0, 0, 0));
                cart.setVelocity(velCopy);
            }, 0L, -1L);
            SchedulerUtil.entityRun(plugin, cart, () -> cart.setVelocity(velCopy), 1L, -1L);
            SchedulerUtil.entityRun(plugin, cart, () -> cart.setVelocity(velCopy), 2L, -1L);
            SchedulerUtil.entityRun(plugin, cart, () -> cart.setVelocity(velCopy), 3L, -1L);
        } else {
            cart.setVelocity(vel);
            cart.setVelocity(vel);
            cart.setVelocity(vel);
            if (scheduleFollowup) {
                final Vector velCopy = vel.clone();
                SchedulerUtil.entityRun(plugin, cart, () -> {
                    cart.setGravity(false);
                    cart.setSlowWhenEmpty(false);
                    cart.setFlyingVelocityMod(new Vector(0, 0, 0));
                    cart.setVelocity(velCopy);
                }, 0L, -1L);
                SchedulerUtil.entityRun(plugin, cart, () -> cart.setVelocity(velCopy), 1L, -1L);
                SchedulerUtil.entityRun(plugin, cart, () -> cart.setVelocity(velCopy), 2L, -1L);
            }
        }
    }
}
