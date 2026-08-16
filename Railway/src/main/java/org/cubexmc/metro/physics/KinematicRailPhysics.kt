package org.cubexmc.metro.physics

import org.bukkit.Location
import org.bukkit.entity.Minecart
import org.bukkit.util.Vector
import org.cubexmc.metro.Metro
import org.cubexmc.metro.model.Stop
import org.cubexmc.metro.train.TrainInstance
import org.cubexmc.metro.util.LocationUtil
import org.cubexmc.metro.util.MinecartNmsUtil
import org.cubexmc.metro.util.MinecartPhysicsUtil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Kinematic rail physics engine - COMPLETE override of vanilla minecart physics.
 *
 * Philosophy:
 * - Vanilla physics is COMPLETELY ignored
 * - Position and velocity are set EVERY tick, overriding any vanilla changes
 * - NO lerping or smoothing that could allow vanilla physics to interfere
 * - Passengers have ZERO effect on movement
 */
open class KinematicRailPhysics : TrainPhysicsEngine {
    private val trail = KinematicTrailBuffer()

    // Kinematic state (our source of truth)
    private var leadX = 0.0
    private var leadY = 0.0
    private var leadZ = 0.0
    private var leadVx = 0.0
    private var leadVy = 0.0
    private var leadVz = 0.0
    private var initialized = false
    private var lastLeadDirection = Vector(1, 0, 0)

    override fun init(train: TrainInstance) {
        trail.clear()
        val lead = train.consist.getLeadCar()
        if (lead != null) {
            val state = KinematicLeadBootstrap.initialize(
                lead.location,
                train.getTravelDirection(),
                lead.velocity,
                train.service.cartSpeed,
            )
            val location = state.location
            val velocity = state.velocity
            leadX = location.x
            leadY = location.y
            leadZ = location.z
            leadVx = velocity.x
            leadVy = velocity.y
            leadVz = velocity.z
            lastLeadDirection = state.facingDirection
        }
        seedTrailFromConsist(train)
        initialized = true
    }

    override fun onDeparture(train: TrainInstance, fromStop: Stop?) {
        init(train)
    }

    override fun tick(train: TrainInstance, timeFraction: Double, currentTick: Long) {
        if (!initialized) {
            init(train)
        }

        val plugin = train.service.plugin
        val lead = train.consist.getLeadCar()
        if (lead == null || lead.isDead) {
            return
        }

        val baseSpeed = train.service.cartSpeed
        val safe = plugin.isSafeSpeedMode
        val travelDirection = train.getTravelDirection()
        val cars = train.consist.getCars()
        val spacing = max(0.1, train.service.trainSpacing)
        val leadCorrection = calculateLeadCorrection(cars, spacing)

        // === LEAD CAR: KINEMATIC CONTROL ===
        if (plugin.isPhysicsLeadKinematic) {
            updateLeadKinematic(lead, baseSpeed, safe, travelDirection, timeFraction, plugin, leadCorrection)
        } else {
            updateLeadVanilla(lead, baseSpeed, safe, travelDirection, plugin, leadCorrection)
        }

        maintainTrail(train)

        // === FOLLOWERS: TRAINCARTS-STYLE VELOCITY CORRECTION ===
        updateFollowers(train, plugin, baseSpeed, spacing)
    }

    /**
     * Update lead car with full kinematic control (TrainCarts-inspired approach).
     * Strategy:
     * 1. Calculate ideal position based on our physics
     * 2. Force minecart to that position (defeating passenger effects)
     * 3. Use aggressive enforcement to maintain control
     */
    private fun updateLeadKinematic(
        lead: Minecart,
        baseSpeed: Double,
        safe: Boolean,
        travelDirection: Vector?,
        timeFraction: Double,
        plugin: Metro,
        spacingCorrection: Vector,
    ) {
        var currentLocation = Location(lead.world, leadX, leadY, leadZ)
        currentLocation = RailPathUtil.projectRequired(currentLocation)

        val configuredMax = max(0.05, baseSpeed)
        lead.maxSpeed = configuredMax
        val command = KinematicLeadMotionPlanner.plan(
            currentLocation,
            lastLeadDirection,
            travelDirection,
            lead.velocity.length(),
            baseSpeed,
            safe,
            plugin.physicsLookaheadBlocks,
            lead.maxSpeed,
            spacingCorrection,
            timeFraction,
        )
        val leadState = command.leadState
        leadX = leadState.x
        leadY = leadState.y
        leadZ = leadState.z
        leadVx = leadState.vx
        leadVy = leadState.vy
        leadVz = leadState.vz

        val leadVelocity = command.correctedVelocity.clone()
        snapToPosition(lead, leadState.location, leadVelocity, command.facingDirection, plugin, baseSpeed)
        lastLeadDirection = command.facingDirection.clone()

        trail.addPoint(leadX, leadY, leadZ, leadVx, leadVy, leadVz)
    }

    /** Update lead car with vanilla physics (just clamp speed). */
    private fun updateLeadVanilla(
        lead: Minecart,
        baseSpeed: Double,
        safe: Boolean,
        travelDirection: Vector?,
        plugin: Metro,
        spacingCorrection: Vector,
    ) {
        val location = lead.location
        val velocity = lead.velocity

        // Update our kinematic state from actual position
        leadX = location.x
        leadY = location.y
        leadZ = location.z

        val configuredMax = max(0.05, baseSpeed)
        lead.maxSpeed = configuredMax

        // Clamp speed if needed
        val currentSpeed = sqrt(velocity.x * velocity.x + velocity.z * velocity.z)
        val railType = LocationUtil.getRailType(location)
        val maxSpeed = LocationUtil.getSafeSpeedForRail(railType, baseSpeed, safe)

        if (currentSpeed > maxSpeed) {
            val scale = maxSpeed / currentSpeed
            velocity.multiply(scale)
            MinecartPhysicsUtil.forceVelocity(lead, velocity, plugin)
        }

        var corrected = KinematicSpacingMath.applySpacingUpdate(velocity, spacingCorrection, lead.maxSpeed)
        corrected = KinematicRailMotionMath.alignVelocityToRail(
            location,
            corrected,
            corrected,
            lastLeadDirection,
        )
        corrected = KinematicSpacingMath.clampVelocity(corrected, maxSpeed)
        MinecartPhysicsUtil.forceVelocity(lead, corrected, plugin)

        leadVx = corrected.x
        leadVy = corrected.y
        leadVz = corrected.z

        val fallback = if (travelDirection != null && travelDirection.lengthSquared() > 1.0e-6) {
            travelDirection.clone()
        } else {
            lastLeadDirection.clone()
        }
        val facingDirection = KinematicRailMotionMath.normalizeOr(corrected, fallback)
        lastLeadDirection = facingDirection.clone()

        trail.addPoint(leadX, leadY, leadZ, leadVx, leadVy, leadVz)
    }

    /**
     * Update all follower cars using TrainCarts' exact approach:
     * 2. Snap to trail position (snapToPosition)
     *
     * TrainCarts snapToPosition: directly sets position and preserves speed magnitude.
     */
    private fun updateFollowers(train: TrainInstance, plugin: Metro, baseSpeed: Double, spacing: Double) {
        val cars = train.consist.getCars()
        val carCount = cars.size
        if (carCount <= 1) {
            return
        }

        // Process each follower cart
        for (index in 1 until carCount) {
            val car = cartOrNull(cars, index)
            if (car == null || car.isDead) {
                continue
            }

            car.setGravity(false)
            car.isSlowWhenEmpty = false
            car.flyingVelocityMod = Vector(0, 0, 0)
            car.maxSpeed = max(0.05, baseSpeed)

            // Step 2: Find target position on trail (spacing * index behind lead)
            val distanceBehind = spacing * index
            val sample = trail.sampleState(distanceBehind, car.world, lastLeadDirection)

            val command = if (sample == null) {
                KinematicFollowerController.resolveWithoutTrail(
                    car.location,
                    car.velocity.clone(),
                    lastLeadDirection,
                    car.maxSpeed,
                    baseSpeed,
                )
            } else {
                KinematicFollowerController.resolveWithSample(
                    sample,
                    car.velocity.clone(),
                    lastLeadDirection,
                    baseSpeed,
                )
            }
            snapToPosition(car, command.targetPosition, command.velocity, command.facing, plugin, baseSpeed)
        }
    }

    /**
     * Calculate spacing correction ONLY for the lead car.
     * This replaces the removed O(N) `calculateSpeedFactor` method.
     *
     * Use O(1) check of just the first two carts.
     */
    private fun calculateLeadCorrection(cars: List<Minecart?>?, spacing: Double): Vector {
        if (cars == null || cars.size < 2) {
            return Vector(0, 0, 0)
        }

        val lead = cars[0]
        val follower = cars[1]
        if (lead == null || lead.isDead || follower == null || follower.isDead) {
            return Vector(0, 0, 0)
        }

        val leadLocation = RailPathUtil.project(lead.location)
        val followerLocation = RailPathUtil.project(follower.location)

        val railDirection: Vector? = RailPathUtil.computeDirection(leadLocation, lastLeadDirection)
        var fallback = if (railDirection != null && railDirection.lengthSquared() > 1.0e-6) {
            railDirection.normalize()
        } else {
            lastLeadDirection.clone().normalize()
        }
        if (fallback.lengthSquared() < 1.0e-8) {
            fallback = Vector(1, 0, 0)
        }
        return KinematicSpacingMath.calculateLeadCorrection(leadLocation, followerLocation, spacing, fallback)
    }

    private fun maintainTrail(train: TrainInstance?) {
        if (train == null) {
            return
        }
        trail.maintain(train.consist.getCars().size, train.service.trainSpacing)
    }

    /**
     * Snap minecart to position (TrainCarts snapToPosition - EXACT COPY).
     * TrainCarts implementation:
     * - entity.setPosition(position.posX, position.posY, position.posZ)
     * - entity.vel.set(position.motX * velocity, position.motY * velocity, position.motZ * velocity)
     */
    private fun snapToPosition(
        cart: Minecart,
        location: Location,
        velocity: Vector?,
        facing: Vector?,
        plugin: Metro?,
        speedLimit: Double,
    ) {
        // Disable vanilla physics, match TrainCarts order of operations
        cart.setGravity(false)
        cart.isSlowWhenEmpty = false
        cart.flyingVelocityMod = Vector(0, 0, 0)
        val configuredMax = max(0.05, if (speedLimit > 0.0) speedLimit else plugin?.cartSpeed ?: 0.4)
        cart.maxSpeed = configuredMax

        val oldYaw = cart.location.yaw
        val command = KinematicSnapMath.prepare(location, velocity, facing, lastLeadDirection, oldYaw)

        val snappedDirect = MinecartNmsUtil.snap(
            cart,
            command.location,
            command.velocity,
            command.yaw,
            command.pitch,
        )
        if (!snappedDirect) {
            cart.teleport(command.location)
            cart.setRotation(command.yaw, command.pitch)
        }

        if (plugin != null) {
            MinecartPhysicsUtil.forceVelocity(cart, command.velocity, plugin)
        } else {
            cart.velocity = command.velocity
        }
    }

    private fun seedTrailFromConsist(train: TrainInstance?) {
        val cars = train?.consist?.getCars()
        if (cars.isNullOrEmpty()) {
            return
        }

        val lead = cartOrNull(cars, 0)
        if (lead == null || lead.isDead) {
            return
        }

        var leadProjected = RailPathUtil.project(lead.location)
        if (leadProjected == null) {
            leadProjected = lead.location
        }

        val leadVelocity = KinematicLeadBootstrap.resolveSeedVelocity(
            Vector(leadVx, leadVy, leadVz),
            lastLeadDirection,
            train.service.cartSpeed,
        )

        trail.seedFromConsist(cars, leadVelocity)
    }

    /** Preserve the old Java implementation's defensive null checks for list elements. */
    private fun cartOrNull(cars: List<Minecart>, index: Int): Minecart? = cars[index]

    override fun onArrival(train: TrainInstance, atStop: Stop?, currentTick: Long) {
        // Nothing needed
    }

    override fun cleanup(train: TrainInstance) {
        trail.clear()
        initialized = false
    }
}
