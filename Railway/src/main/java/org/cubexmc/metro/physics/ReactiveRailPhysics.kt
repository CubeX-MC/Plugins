package org.cubexmc.metro.physics

import java.util.UUID
import org.bukkit.Location
import org.bukkit.entity.Minecart
import org.bukkit.util.Vector
import org.cubexmc.metro.Metro
import org.cubexmc.metro.model.Stop
import org.cubexmc.metro.train.TrainInstance
import org.cubexmc.metro.util.LocationUtil
import org.cubexmc.metro.util.MinecartNmsUtil
import org.cubexmc.metro.util.MinecartPhysicsUtil
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Reactive spacing controller. No teleports; adjusts velocities to maintain spacing like vanilla.
 * Uses PD controller with enhanced sensitivity to maintain consistent spacing.
 */
open class ReactiveRailPhysics : TrainPhysicsEngine {

    private val cartStates = ReactiveCartStateStore()

    override fun init(train: TrainInstance) {
        cartStates.clear()
    }

    override fun onDeparture(train: TrainInstance, fromStop: Stop?) {
        // no-op
    }

    override fun tick(train: TrainInstance, timeFraction: Double, currentTick: Long) {
        val plugin = train.service.plugin
        val cars = train.consist.getCars()
        if (cars.isEmpty()) {
            return
        }

        // Prune stale entries - single pass collection of valid UUIDs
        val validUuids = HashSet<UUID>()
        for (index in cars.indices) {
            val cart = cartOrNull(cars, index)
            if (cart != null && !cart.isDead) {
                validUuids.add(cart.uniqueId)
            }
        }
        cartStates.retainAll(validUuids)

        val baseSpeed = train.service.cartSpeed
        for (index in cars.indices) {
            val car = cartOrNull(cars, index)
            if (car != null && !car.isDead) {
                car.isSlowWhenEmpty = false
                car.setGravity(false)
                car.flyingVelocityMod = Vector(0, 0, 0)
                car.maxSpeed = max(0.05, baseSpeed)
            }
        }

        val safeMode = train.service.plugin.isSafeSpeedMode

        // Lead: clamp to safe/base speed in current rail context
        val lead = cartOrNull(cars, 0)
        if (lead != null && !lead.isDead) {
            val leadProjected = requireRailLocation(getCommandedLocation(lead), lead)
            var fallbackDirection: Vector? = getCommandedVelocity(lead)
            if (fallbackDirection == null || fallbackDirection.lengthSquared() < 1.0e-6) {
                fallbackDirection = train.getTravelDirection()
            }
            if (fallbackDirection == null || fallbackDirection.lengthSquared() < 1.0e-6) {
                fallbackDirection = Vector(1, 0, 0)
            }

            var direction = computeRailDirection(leadProjected, fallbackDirection, getLastDirection(lead))
            direction = enforceForwardOrientation(
                direction,
                fallbackDirection,
                getLastDirection(lead),
                train.getTravelDirection(),
            )

            val maximum = computeBlendedSafeSpeed(leadProjected, direction, baseSpeed, safeMode, plugin)
            // Enforce the computed target speed exactly (no blending)
            val targetSpeed = max(0.0, maximum)

            var targetVelocity = direction.multiply(targetSpeed)
            targetVelocity = alignToRail(lead, leadProjected, targetVelocity, direction)
            targetVelocity = clampVelocity(targetVelocity, baseSpeed)
            val facing = if (targetVelocity.lengthSquared() > 1.0e-8) {
                targetVelocity.clone().normalize()
            } else {
                direction
            }

            val displacement = targetVelocity.clone().multiply(timeFraction)
            var targetLocation = leadProjected.clone().add(displacement.x, displacement.y, displacement.z)
            targetLocation = requireRailLocation(targetLocation, lead)

            rememberDirection(lead, facing)
            rememberVelocity(lead, targetVelocity)
            snapToProjectedPosition(lead, targetLocation, targetVelocity, facing, plugin)
            rememberPosition(lead, targetLocation)
            maybeBoostAscendingRail(train, lead, baseSpeed)
        }

        // Followers: Enhanced PD controller with speed-based dynamic spacing
        for (index in 1 until cars.size) {
            val previous = cartOrNull(cars, index - 1)
            val car = cartOrNull(cars, index)
            if (previous == null || car == null || previous.isDead || car.isDead) {
                continue
            }

            val previousProjected = requireRailLocation(getCommandedLocation(previous), previous)
            val carProjected = requireRailLocation(getCommandedLocation(car), car)
            val toPrevious = previousProjected.toVector().subtract(carProjected.toVector())
            val distance = toPrevious.length()
            if (distance < 1.0e-6) {
                continue
            }
            val baseDirection = toPrevious.clone().multiply(1.0 / distance) // from car -> previous
            val carVelocity = getCommandedVelocity(car)
            val previousVelocity = getCommandedVelocity(previous)
            var direction = computeRailDirection(carProjected, baseDirection, getLastDirection(car))
            direction = enforceForwardOrientation(
                direction,
                carVelocity,
                getLastDirection(car),
                baseDirection,
                previousVelocity,
                getLastDirection(previous),
                train.getTravelDirection(),
            )
            val previousAlong = previousVelocity.dot(direction)
            val carAlong = carVelocity.dot(direction)

            val maximum = computeBlendedSafeSpeed(carProjected, direction, baseSpeed, safeMode, plugin)
            val targetAlong = ReactiveSpacingDecisions.followerTargetAlongSpeed(
                previousAlong,
                carAlong,
                distance,
                timeFraction,
                maximum,
            )

            // Enforce the computed along-track speed exactly (no blending)
            var finalVelocity = direction.multiply(targetAlong)
            finalVelocity = alignToRail(car, carProjected, finalVelocity, direction)
            finalVelocity = clampVelocity(finalVelocity, baseSpeed)
            val facing = if (finalVelocity.lengthSquared() > 1.0e-8) {
                finalVelocity.clone().normalize()
            } else {
                direction
            }

            val displacement = finalVelocity.clone().multiply(timeFraction)
            var targetLocation = carProjected.clone().add(displacement.x, displacement.y, displacement.z)
            targetLocation = requireRailLocation(targetLocation, car)

            rememberDirection(car, facing)
            rememberVelocity(car, finalVelocity)
            snapToProjectedPosition(car, targetLocation, finalVelocity, facing, plugin)
            rememberPosition(car, targetLocation)
            maybeBoostAscendingRail(train, car, baseSpeed)
        }
    }

    override fun onArrival(train: TrainInstance, atStop: Stop?, currentTick: Long) {
        // no-op
    }

    override fun cleanup(train: TrainInstance) {
        // no-op for reactive
        cartStates.clear()
    }

    private fun computeBlendedSafeSpeed(
        currentLocation: Location?,
        direction: Vector?,
        baseSpeed: Double,
        safeMode: Boolean,
        plugin: Metro,
    ): Double {
        val reference = currentLocation?.clone() ?: return max(0.05, baseSpeed)

        var baseline = min(
            baseSpeed,
            LocationUtil.getSafeSpeedForRail(LocationUtil.getRailType(reference), baseSpeed, safeMode),
        )
        baseline = max(0.05, baseline)
        if (!safeMode) {
            return baseline
        }

        val lookahead = plugin.physicsLookaheadBlocks
        if (lookahead <= 0) {
            return baseline
        }

        var resolvedDirection = direction?.clone() ?: Vector(1, 0, 0)
        if (resolvedDirection.lengthSquared() < 1.0e-6) {
            resolvedDirection = Vector(1, 0, 0)
        } else {
            resolvedDirection.normalize()
        }

        var target = baseline
        for (index in 1..lookahead) {
            val ahead = reference.clone().add(resolvedDirection.clone().multiply(index))
            val aheadSafe = min(
                baseSpeed,
                LocationUtil.getSafeSpeedForRail(LocationUtil.getRailType(ahead), baseSpeed, true),
            )
            if (aheadSafe < target - 1.0e-4) {
                // Gradual deceleration: blend factor increases as we get closer
                val blendFactor = index.toDouble() / (lookahead + 1)
                val blended = target * blendFactor + aheadSafe * (1.0 - blendFactor)
                target = min(target, blended)
            }
        }
        // Final safety check: never allow negative or zero speed
        return max(0.05, target)
    }

    private fun maybeBoostAscendingRail(train: TrainInstance, cart: Minecart?, baseSpeed: Double) {
        if (cart == null || cart.isDead) {
            return
        }
        if (!LocationUtil.isPoweredAscendingRailPowered(cart.location)) {
            return
        }
        var fallback = getCommandedVelocity(cart)
        if (fallback.lengthSquared() < 1.0e-6) {
            fallback = getLastDirection(cart)
        }
        if (fallback.lengthSquared() < 1.0e-6) {
            val travel = train.getTravelDirection()
            fallback = travel?.clone() ?: Vector(0, 0, 0)
        }
        var direction = LocationUtil.railDirection(cart.location, fallback)
        if (direction == null || direction.lengthSquared() == 0.0) {
            direction = if (fallback.lengthSquared() > 1.0e-6) {
                fallback.clone()
            } else {
                val velocity = cart.velocity
                if (velocity.lengthSquared() == 0.0) {
                    return
                }
                velocity.clone()
            }
        }
        direction.normalize()
        val minimumSpeed = max(0.4, baseSpeed * 0.75)
        val boost = direction.multiply(minimumSpeed)
        MinecartPhysicsUtil.forceVelocity(cart, boost, train.service.plugin)
    }

    private fun getCommandedVelocity(cart: Minecart?): Vector {
        if (cart == null) {
            return Vector(0, 0, 0)
        }
        val stored = cartStates.getVelocity(cart.uniqueId)
        if (stored != null) {
            return stored
        }
        val current = velocityOrNull(cart)
        return current?.clone() ?: Vector(0, 0, 0)
    }

    private fun getCommandedLocation(cart: Minecart?): Location? {
        if (cart == null || cart.isDead) {
            return null
        }
        val stored = cartStates.getPosition(cart.uniqueId)
        if (stored != null) {
            val ensured = requireRailLocation(stored, cart)
            cartStates.rememberPosition(cart.uniqueId, ensured)
            return ensured
        }
        val projected: Location? = RailPathUtil.project(cart.location)
        val base = requireRailLocation(projected, cart)
        cartStates.rememberPosition(cart.uniqueId, base)
        return base
    }

    private fun rememberPosition(cart: Minecart?, location: Location?) {
        if (cart == null || location == null) {
            return
        }
        cartStates.rememberPosition(cart.uniqueId, requireRailLocation(location, cart))
    }

    private fun rememberVelocity(cart: Minecart?, velocity: Vector?) {
        if (cart != null && velocity != null) {
            cartStates.rememberVelocity(cart.uniqueId, velocity)
        }
    }

    private fun rememberDirection(cart: Minecart?, velocity: Vector?) {
        if (cart == null || velocity == null) {
            return
        }
        val direction = velocity.clone()
        if (direction.lengthSquared() < 1.0e-8) {
            return
        }
        cartStates.rememberDirection(cart.uniqueId, direction)
    }

    private fun getLastDirection(cart: Minecart?): Vector {
        if (cart == null) {
            return Vector(1, 0, 0)
        }
        val stored = cartStates.getDirection(cart.uniqueId)
        if (stored != null && stored.lengthSquared() > 1.0e-8) {
            return stored
        }
        return Vector(1, 0, 0)
    }

    private fun computeRailDirection(
        projectedLocation: Location?,
        fallback: Vector?,
        previousDirection: Vector?,
    ): Vector {
        var preferred = if (fallback != null && fallback.lengthSquared() > 1.0e-8) {
            fallback.clone()
        } else {
            previousDirection?.clone() ?: Vector(1, 0, 0)
        }
        if (preferred.lengthSquared() < 1.0e-8) {
            preferred = Vector(1, 0, 0)
        }
        preferred.normalize()

        val previous = if (previousDirection != null && previousDirection.lengthSquared() > 1.0e-8) {
            previousDirection.clone().normalize()
        } else {
            null
        }

        val snapped = projectedLocation?.clone() ?: return preferred
        val railDirection: Vector? = RailPathUtil.computeDirection(snapped, preferred)
        var result = if (railDirection != null && railDirection.lengthSquared() > 1.0e-8) {
            railDirection.normalize()
        } else {
            preferred.clone()
        }

        val railType = LocationUtil.getRailType(snapped)
        val isSlope = railType == LocationUtil.RailType.ASCENDING || railType == LocationUtil.RailType.DESCENDING

        if (previous != null) {
            val threshold = if (isSlope) 0.99 else 0.707
            val blendFactor = if (isSlope) 0.85 else 0.4

            val dot = result.dot(previous)
            if (dot < threshold) {
                val blended = previous.clone().multiply(1.0 - blendFactor)
                    .add(result.clone().multiply(blendFactor))
                result = if (blended.lengthSquared() > 1.0e-8) {
                    blended.normalize()
                } else {
                    previous.clone()
                }
            }
        }
        return result
    }

    private fun alignToRail(
        cart: Minecart?,
        projectedLocation: Location?,
        velocity: Vector?,
        fallbackDirection: Vector?,
    ): Vector {
        if (cart == null || velocity == null) {
            return Vector()
        }
        val base = velocity.clone()
        var preferred = if (fallbackDirection != null && fallbackDirection.lengthSquared() > 1.0e-8) {
            fallbackDirection.clone()
        } else {
            getLastDirection(cart)
        }
        if (preferred.lengthSquared() < 1.0e-8) {
            preferred = if (base.lengthSquared() > 1.0e-8) base.clone() else getLastDirection(cart)
        }
        val snapped = projectedLocation?.clone() ?: RailPathUtil.project(cart.location)
        val railDirection: Vector? = RailPathUtil.computeDirection(snapped, preferred)
        if (railDirection != null && railDirection.lengthSquared() > 1.0e-8) {
            val speed = base.length()
            return railDirection.normalize().multiply(speed)
        }
        return base
    }

    private fun clampVelocity(velocity: Vector?, maxSpeed: Double): Vector {
        if (velocity == null) {
            return Vector()
        }
        val result = velocity.clone()
        val limit = max(0.05, maxSpeed)
        val length = result.length()
        if (length > limit) {
            result.multiply(limit / length)
        }
        return result
    }

    private fun enforceForwardOrientation(candidate: Vector?, vararg references: Vector?): Vector {
        if (candidate == null) {
            return Vector()
        }
        val result = candidate.clone()
        if (result.lengthSquared() < 1.0e-8) {
            return result
        }

        val normalized = result.clone().normalize()
        for (reference in references) {
            if (reference == null || reference.lengthSquared() < 1.0e-8) {
                continue
            }
            val referenceNormalized = reference.clone().normalize()
            val dot = normalized.dot(referenceNormalized)
            if (abs(dot) < 0.05) {
                // When nearly perpendicular, try the next reference for a clearer signal
                continue
            }
            if (dot < 0.0) {
                result.multiply(-1.0)
                normalized.multiply(-1.0)
            }
            return result
        }
        return result
    }

    private fun snapToProjectedPosition(
        cart: Minecart?,
        projectedLocation: Location?,
        velocity: Vector?,
        facing: Vector?,
        plugin: Metro?,
    ) {
        if (cart == null || cart.isDead || projectedLocation == null) {
            return
        }

        val resolvedVelocity = velocity?.clone() ?: Vector()
        var direction = when {
            facing != null && facing.lengthSquared() > 1.0e-8 -> facing.clone()
            resolvedVelocity.lengthSquared() > 1.0e-8 -> resolvedVelocity.clone()
            else -> getLastDirection(cart)
        }
        if (direction.lengthSquared() < 1.0e-8) {
            direction = Vector(1, 0, 0)
        }
        direction.normalize()

        val dx = direction.x
        val dy = direction.y
        val dz = direction.z
        val horizontal = sqrt(dx * dx + dz * dz)
        val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        val pitch = Math.toDegrees(atan2(-dy, horizontal)).toFloat()

        val target = projectedLocation.clone()
        target.yaw = yaw
        target.pitch = pitch

        val snapped = MinecartNmsUtil.snap(cart, target, resolvedVelocity, yaw, pitch)
        if (!snapped) {
            cart.teleport(target)
            cart.setRotation(yaw, pitch)
        }

        if (plugin != null) {
            MinecartPhysicsUtil.forceVelocity(cart, resolvedVelocity, plugin)
        } else {
            cart.velocity = resolvedVelocity
        }
    }

    private fun ensureRailLocation(candidate: Location?, reference: Minecart?): Location? {
        if (candidate == null) {
            if (reference == null) {
                return null
            }
            return RailPathUtil.project(reference.location)
        }

        var check = candidate.clone()
        if (!LocationUtil.isRail(check)) {
            val snapped = LocationUtil.snapToRail(check, check.world)
            if (snapped != null && LocationUtil.isRail(snapped)) {
                check = snapped
            } else if (reference != null) {
                val fromCart: Location? = RailPathUtil.project(reference.location)
                if (fromCart != null && LocationUtil.isRail(fromCart)) {
                    check = fromCart
                } else {
                    val snapCart = LocationUtil.snapToRail(reference.location, reference.world)
                    if (snapCart != null && LocationUtil.isRail(snapCart)) {
                        check = snapCart
                    }
                }
            }
        }

        val projected: Location? = RailPathUtil.project(check)
        return projected ?: check
    }

    private fun requireRailLocation(candidate: Location?, reference: Minecart): Location =
        ensureRailLocation(candidate, reference) ?: throw NullPointerException("rail location")

    /** Preserve the old Java implementation's defensive null checks for list elements. */
    private fun cartOrNull(cars: List<Minecart>, index: Int): Minecart? = cars[index]

    /** Preserve the old Java implementation's defensive null check around Bukkit velocity. */
    private fun velocityOrNull(cart: Minecart): Vector? = cart.velocity
}
