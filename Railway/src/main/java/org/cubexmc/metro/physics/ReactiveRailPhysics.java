package org.cubexmc.metro.physics;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Minecart;
import org.bukkit.util.Vector;
import org.cubexmc.metro.Metro;
import org.cubexmc.metro.model.Stop;
import org.cubexmc.metro.train.TrainInstance;
import org.cubexmc.metro.util.LocationUtil;
import org.cubexmc.metro.util.MinecartPhysicsUtil;
import org.cubexmc.metro.util.MinecartNmsUtil;
// RailPathUtil is package-private; accessible without import.

/**
 * Reactive spacing controller. No teleports; adjusts velocities to maintain
 * spacing like vanilla.
 * Uses PD controller with enhanced sensitivity to maintain consistent spacing.
 */
public class ReactiveRailPhysics implements TrainPhysicsEngine {

    private final ReactiveCartStateStore cartStates = new ReactiveCartStateStore();

    @Override
    public void init(TrainInstance train) {
        cartStates.clear();
    }

    @Override
    public void onDeparture(TrainInstance train, Stop fromStop) {
        // no-op
    }

    @Override
    public void tick(TrainInstance train, double timeFraction, long currentTick) {
        Metro plugin = train.getService().getPlugin();
        List<Minecart> cars = train.getConsist().getCars();
        if (cars.isEmpty())
            return;

        // Prune stale entries - single pass collection of valid UUIDs
        Set<UUID> validUuids = new HashSet<>();
        for (Minecart cart : cars) {
            if (cart != null && !cart.isDead()) {
                validUuids.add(cart.getUniqueId());
            }
        }
        cartStates.retainAll(validUuids);

        double base = train.getService().getCartSpeed();
        for (Minecart car : cars) {
            if (car != null && !car.isDead()) {
                car.setSlowWhenEmpty(false);
                car.setGravity(false);
                car.setFlyingVelocityMod(new org.bukkit.util.Vector(0, 0, 0));
                car.setMaxSpeed(Math.max(0.05, base));
            }
        }

        boolean safeMode = train.getService().getPlugin().isSafeSpeedMode();

        // Lead: clamp to safe/base speed in current rail context
        Minecart lead = cars.get(0);
        if (lead != null && !lead.isDead()) {
            Location leadProjected = ensureRailLocation(getCommandedLocation(lead), lead);
            Vector fallbackDir = getCommandedVelocity(lead);
            if (fallbackDir == null || fallbackDir.lengthSquared() < 1.0e-6) {
                fallbackDir = train.getTravelDirection();
            }
            if (fallbackDir == null || fallbackDir.lengthSquared() < 1.0e-6) {
                fallbackDir = new Vector(1, 0, 0);
            }

            Vector dir = computeRailDirection(leadProjected, fallbackDir, getLastDirection(lead));
            dir = enforceForwardOrientation(dir,
                    fallbackDir,
                    getLastDirection(lead),
                    train.getTravelDirection());

            double max = computeBlendedSafeSpeed(leadProjected, dir, base, safeMode, plugin);
            // Enforce the computed target speed exactly (no blending)
            double targetSpeed = Math.max(0.0, max);

            Vector targetVel = dir.multiply(targetSpeed);
            targetVel = alignToRail(lead, leadProjected, targetVel, dir);
            targetVel = clampVelocity(targetVel, base);
            Vector facing = targetVel.lengthSquared() > 1.0e-8 ? targetVel.clone().normalize() : dir;

            Vector displacement = targetVel.clone().multiply(timeFraction);
            Location targetLoc = leadProjected.clone().add(displacement.getX(), displacement.getY(),
                    displacement.getZ());
            targetLoc = ensureRailLocation(targetLoc, lead);

            rememberDirection(lead, facing);
            rememberVelocity(lead, targetVel);
            snapToProjectedPosition(lead, targetLoc, targetVel, facing, plugin);
            rememberPosition(lead, targetLoc);
            maybeBoostAscendingRail(train, lead, base);
        }

        // Followers: Enhanced PD controller with speed-based dynamic spacing
        for (int i = 1; i < cars.size(); i++) {
            Minecart prev = cars.get(i - 1);
            Minecart car = cars.get(i);
            if (prev == null || car == null || prev.isDead() || car.isDead())
                continue;

            Location prevProj = ensureRailLocation(getCommandedLocation(prev), prev);
            Location carProj = ensureRailLocation(getCommandedLocation(car), car);
            Vector toPrev = prevProj.toVector().subtract(carProj.toVector());
            double dist = toPrev.length();
            if (dist < 1.0e-6)
                continue;
            Vector baseDir = toPrev.clone().multiply(1.0 / dist); // from car -> prev
            Vector vCar = getCommandedVelocity(car);
            Vector vPrev = getCommandedVelocity(prev);
            Vector dir = computeRailDirection(carProj, baseDir, getLastDirection(car));
            dir = enforceForwardOrientation(dir,
                    vCar,
                    getLastDirection(car),
                    baseDir,
                    vPrev,
                    getLastDirection(prev),
                    train.getTravelDirection());
            double prevAlong = vPrev.dot(dir);
            double carAlong = vCar.dot(dir);

            double max = computeBlendedSafeSpeed(carProj, dir, base, safeMode, plugin);

            double targetAlong = ReactiveSpacingDecisions.followerTargetAlongSpeed(
                    prevAlong,
                    carAlong,
                    dist,
                    timeFraction,
                    max);

            // Enforce the computed along-track speed exactly (no blending)

            Vector finalVel = dir.multiply(targetAlong);
            finalVel = alignToRail(car, carProj, finalVel, dir);
            finalVel = clampVelocity(finalVel, base);
            Vector facing = finalVel.lengthSquared() > 1.0e-8 ? finalVel.clone().normalize() : dir;

            Vector displacement = finalVel.clone().multiply(timeFraction);
            Location targetLoc = carProj.clone().add(displacement.getX(), displacement.getY(), displacement.getZ());
            targetLoc = ensureRailLocation(targetLoc, car);

            rememberDirection(car, facing);
            rememberVelocity(car, finalVel);
            snapToProjectedPosition(car, targetLoc, finalVel, facing, plugin);
            rememberPosition(car, targetLoc);
            maybeBoostAscendingRail(train, car, base);
        }
    }

    @Override
    public void onArrival(TrainInstance train, Stop atStop, long currentTick) {
        // no-op
    }

    @Override
    public void cleanup(TrainInstance train) {
        // no-op for reactive
        cartStates.clear();
    }

    private double computeBlendedSafeSpeed(Location currentLoc, Vector direction,
            double baseSpeed, boolean safeMode, Metro plugin) {
        Location reference = currentLoc == null ? null : currentLoc.clone();
        if (reference == null) {
            return Math.max(0.05, baseSpeed);
        }

        double baseline = Math.min(baseSpeed,
                LocationUtil.getSafeSpeedForRail(LocationUtil.getRailType(reference), baseSpeed, safeMode));
        baseline = Math.max(0.05, baseline);
        if (!safeMode) {
            return baseline;
        }

        int lookahead = plugin.getPhysicsLookaheadBlocks();
        if (lookahead <= 0) {
            return baseline;
        }

        Vector dir = (direction != null) ? direction.clone() : new Vector(1, 0, 0);
        if (dir.lengthSquared() < 1.0e-6) {
            dir = new Vector(1, 0, 0);
        } else {
            dir.normalize();
        }

        double target = baseline;
        for (int i = 1; i <= lookahead; i++) {
            Location ahead = reference.clone().add(dir.clone().multiply(i));
            double aheadSafe = Math.min(baseSpeed,
                    LocationUtil.getSafeSpeedForRail(LocationUtil.getRailType(ahead), baseSpeed, true));
            if (aheadSafe < target - 1.0e-4) {
                // Gradual deceleration: blend factor increases as we get closer
                double blendFactor = (double) i / (lookahead + 1);
                double blended = target * blendFactor + aheadSafe * (1.0 - blendFactor);
                target = Math.min(target, blended);
            }
        }
        // Final safety check: never allow negative or zero speed
        return Math.max(0.05, target);
    }

    private void maybeBoostAscendingRail(TrainInstance train, Minecart cart, double baseSpeed) {
        if (cart == null || cart.isDead())
            return;
        if (!LocationUtil.isPoweredAscendingRailPowered(cart.getLocation()))
            return;
        Vector fallback = getCommandedVelocity(cart);
        if (fallback == null || fallback.lengthSquared() < 1.0e-6) {
            fallback = getLastDirection(cart);
        }
        if (fallback == null || fallback.lengthSquared() < 1.0e-6) {
            Vector travel = train.getTravelDirection();
            fallback = (travel != null) ? travel.clone() : new Vector(0, 0, 0);
        }
        Vector dir = LocationUtil.railDirection(cart.getLocation(), fallback);
        if (dir == null || dir.lengthSquared() == 0) {
            if (fallback != null && fallback.lengthSquared() > 1.0e-6) {
                dir = fallback.clone();
            } else {
                Vector vel = cart.getVelocity();
                if (vel.lengthSquared() == 0)
                    return;
                dir = vel.clone();
            }
        }
        dir.normalize();
        double minSpeed = Math.max(0.4, baseSpeed * 0.75);
        Vector boost = dir.multiply(minSpeed);
        MinecartPhysicsUtil.forceVelocity(cart, boost, train.getService().getPlugin());
    }

    private Vector getCommandedVelocity(Minecart cart) {
        if (cart == null) {
            return new Vector(0, 0, 0);
        }
        Vector stored = cartStates.getVelocity(cart.getUniqueId());
        if (stored != null) {
            return stored;
        }
        Vector current = cart.getVelocity();
        return current != null ? current.clone() : new Vector(0, 0, 0);
    }

    private Location getCommandedLocation(Minecart cart) {
        if (cart == null || cart.isDead()) {
            return null;
        }
        Location stored = cartStates.getPosition(cart.getUniqueId());
        if (stored != null) {
            Location ensured = ensureRailLocation(stored, cart);
            cartStates.rememberPosition(cart.getUniqueId(), ensured);
            return ensured;
        }
        Location projected = RailPathUtil.project(cart.getLocation());
        Location base = ensureRailLocation(projected, cart);
        cartStates.rememberPosition(cart.getUniqueId(), base);
        return base;
    }

    private void rememberPosition(Minecart cart, Location location) {
        if (cart == null || location == null) {
            return;
        }
        cartStates.rememberPosition(cart.getUniqueId(), ensureRailLocation(location, cart));
    }

    private void rememberVelocity(Minecart cart, Vector velocity) {
        if (cart != null && velocity != null) {
            cartStates.rememberVelocity(cart.getUniqueId(), velocity);
        }
    }

    private void rememberDirection(Minecart cart, Vector velocity) {
        if (cart == null || velocity == null) {
            return;
        }
        Vector dir = velocity.clone();
        if (dir.lengthSquared() < 1.0e-8) {
            return;
        }
        cartStates.rememberDirection(cart.getUniqueId(), dir);
    }

    private Vector getLastDirection(Minecart cart) {
        if (cart == null) {
            return new Vector(1, 0, 0);
        }
        Vector stored = cartStates.getDirection(cart.getUniqueId());
        if (stored != null && stored.lengthSquared() > 1.0e-8) {
            return stored;
        }
        return new Vector(1, 0, 0);
    }

    private Vector computeRailDirection(Location projectedLocation, Vector fallback, Vector previousDir) {
        Vector preferred = (fallback != null && fallback.lengthSquared() > 1.0e-8)
                ? fallback.clone()
                : (previousDir != null ? previousDir.clone() : new Vector(1, 0, 0));
        if (preferred.lengthSquared() < 1.0e-8) {
            preferred = new Vector(1, 0, 0);
        }
        preferred.normalize();

        Vector previous = (previousDir != null && previousDir.lengthSquared() > 1.0e-8)
                ? previousDir.clone().normalize()
                : null;

        Location snapped = projectedLocation != null ? projectedLocation.clone() : null;
        if (snapped == null) {
            return preferred;
        }
        Vector railDir = RailPathUtil.computeDirection(snapped, preferred);
        Vector result = (railDir != null && railDir.lengthSquared() > 1.0e-8)
                ? railDir.normalize()
                : preferred.clone();

        LocationUtil.RailType railType = LocationUtil.getRailType(snapped);
        boolean isSlope = railType == LocationUtil.RailType.ASCENDING || railType == LocationUtil.RailType.DESCENDING;

        if (previous != null) {
            double threshold = isSlope ? 0.99 : 0.707;
            double blendFactor = isSlope ? 0.85 : 0.4;

            double dot = result.dot(previous);
            if (dot < threshold) {
                double blend = blendFactor;
                Vector blended = previous.clone().multiply(1.0 - blend).add(result.clone().multiply(blend));
                if (blended.lengthSquared() > 1.0e-8) {
                    result = blended.normalize();
                } else {
                    result = previous.clone();
                }
            }
        }
        return result;
    }

    private Vector alignToRail(Minecart cart, Location projectedLocation, Vector velocity, Vector fallbackDir) {
        if (cart == null || velocity == null) {
            return new Vector();
        }
        Vector base = velocity.clone();
        Vector preferred = (fallbackDir != null && fallbackDir.lengthSquared() > 1.0e-8)
                ? fallbackDir.clone()
                : getLastDirection(cart);
        if (preferred.lengthSquared() < 1.0e-8) {
            preferred = base.lengthSquared() > 1.0e-8 ? base.clone() : getLastDirection(cart);
        }
        Location snapped = (projectedLocation != null) ? projectedLocation.clone()
                : RailPathUtil.project(cart.getLocation());
        Vector railDir = RailPathUtil.computeDirection(snapped, preferred);
        if (railDir != null && railDir.lengthSquared() > 1.0e-8) {
            double speed = base.length();
            return railDir.normalize().multiply(speed);
        }
        return base;
    }

    private Vector clampVelocity(Vector velocity, double maxSpeed) {
        if (velocity == null) {
            return new Vector();
        }
        Vector result = velocity.clone();
        double limit = Math.max(0.05, maxSpeed);
        double len = result.length();
        if (len > limit) {
            result.multiply(limit / len);
        }
        return result;
    }

    private Vector enforceForwardOrientation(Vector candidate, Vector... references) {
        if (candidate == null) {
            return new Vector();
        }
        Vector result = candidate.clone();
        double lenSq = result.lengthSquared();
        if (lenSq < 1.0e-8) {
            return result;
        }

        Vector normalized = result.clone().normalize();
        for (Vector ref : references) {
            if (ref == null || ref.lengthSquared() < 1.0e-8) {
                continue;
            }
            Vector refNorm = ref.clone().normalize();
            double dot = normalized.dot(refNorm);
            if (Math.abs(dot) < 0.05) {
                // When nearly perpendicular, try the next reference for a clearer signal
                continue;
            }
            if (dot < 0.0) {
                result.multiply(-1.0);
                normalized.multiply(-1.0);
            }
            return result;
        }
        return result;
    }

    private void snapToProjectedPosition(Minecart cart, Location projectedLoc, Vector velocity,
            Vector facing, Metro plugin) {
        if (cart == null || cart.isDead() || projectedLoc == null) {
            return;
        }

        Vector vel = velocity != null ? velocity.clone() : new Vector();
        Vector direction;
        if (facing != null && facing.lengthSquared() > 1.0e-8) {
            direction = facing.clone();
        } else if (vel.lengthSquared() > 1.0e-8) {
            direction = vel.clone();
        } else {
            direction = getLastDirection(cart);
        }
        if (direction.lengthSquared() < 1.0e-8) {
            direction = new Vector(1, 0, 0);
        }
        direction.normalize();

        double dx = direction.getX();
        double dy = direction.getY();
        double dz = direction.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(Math.atan2(-dy, horiz));

        Location target = projectedLoc.clone();
        target.setYaw(yaw);
        target.setPitch(pitch);

        boolean snapped = MinecartNmsUtil.snap(cart, target, vel, yaw, pitch);
        if (!snapped) {
            cart.teleport(target);
            cart.setRotation(yaw, pitch);
        }

        if (plugin != null) {
            MinecartPhysicsUtil.forceVelocity(cart, vel, plugin);
        } else {
            cart.setVelocity(vel);
        }
    }

    private Location ensureRailLocation(Location candidate, Minecart reference) {
        if (candidate == null) {
            if (reference == null) {
                return null;
            }
            return RailPathUtil.project(reference.getLocation());
        }

        Location check = candidate.clone();
        if (!LocationUtil.isRail(check)) {
            Location snapped = LocationUtil.snapToRail(check, check.getWorld());
            if (snapped != null && LocationUtil.isRail(snapped)) {
                check = snapped;
            } else if (reference != null) {
                Location fromCart = RailPathUtil.project(reference.getLocation());
                if (fromCart != null && LocationUtil.isRail(fromCart)) {
                    check = fromCart;
                } else {
                    Location snapCart = LocationUtil.snapToRail(reference.getLocation(), reference.getWorld());
                    if (snapCart != null && LocationUtil.isRail(snapCart)) {
                        check = snapCart;
                    }
                }
            }
        }

        Location projected = RailPathUtil.project(check);
        return projected != null ? projected : check;
    }

}
