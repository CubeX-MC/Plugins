package org.cubexmc.metro.physics;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.entity.Minecart;
import org.bukkit.util.Vector;
import org.cubexmc.metro.Metro;
import org.cubexmc.metro.model.Stop;
import org.cubexmc.metro.train.TrainInstance;
import org.cubexmc.metro.util.LocationUtil;
import org.cubexmc.metro.util.MinecartNmsUtil;
import org.cubexmc.metro.util.MinecartPhysicsUtil;

/**
 * Kinematic rail physics engine - COMPLETE override of vanilla minecart
 * physics.
 * 
 * Philosophy:
 * - Vanilla physics is COMPLETELY ignored
 * - Position and velocity are set EVERY tick, overriding any vanilla changes
 * - NO lerping or smoothing that could allow vanilla physics to interfere
 * - Passengers have ZERO effect on movement
 */
public class KinematicRailPhysics implements TrainPhysicsEngine {
    private final KinematicTrailBuffer trail = new KinematicTrailBuffer();

    // Kinematic state (our source of truth)
    private double leadX, leadY, leadZ;
    private double leadVx, leadVy, leadVz;
    private boolean initialized;
    private Vector lastLeadDirection = new Vector(1, 0, 0);

    @Override
    public void init(TrainInstance train) {
        trail.clear();
        Minecart lead = train.getConsist().getLeadCar();
        if (lead != null) {
            KinematicLeadBootstrap.BootstrapState state = KinematicLeadBootstrap.initialize(
                    lead.getLocation(),
                    train.getTravelDirection(),
                    lead.getVelocity(),
                    train.getService().getCartSpeed());
            Location loc = state.location();
            Vector velocity = state.velocity();
            leadX = loc.getX();
            leadY = loc.getY();
            leadZ = loc.getZ();
            leadVx = velocity.getX();
            leadVy = velocity.getY();
            leadVz = velocity.getZ();
            lastLeadDirection = state.facingDirection();
        }
        seedTrailFromConsist(train);
        initialized = true;
    }

    @Override
    public void onDeparture(TrainInstance train, Stop fromStop) {
        init(train);
    }

    @Override
    public void tick(TrainInstance train, double timeFraction, long currentTick) {
        if (!initialized)
            init(train);

        Metro plugin = train.getService().getPlugin();
        Minecart lead = train.getConsist().getLeadCar();
        if (lead == null || lead.isDead())
            return;

        double baseSpeed = train.getService().getCartSpeed();
        boolean safe = plugin.isSafeSpeedMode();
        Vector travelDir = train.getTravelDirection();
        List<Minecart> cars = train.getConsist().getCars();
        double spacing = Math.max(0.1, train.getService().getTrainSpacing());
        // === LEAD CAR: KINEMATIC CONTROL ===
        if (plugin.isPhysicsLeadKinematic()) {
            Vector leadCorrection = calculateLeadCorrection(cars, spacing);
            updateLeadKinematic(lead, baseSpeed, safe, travelDir, timeFraction, plugin, leadCorrection);
        } else {
            Vector leadCorrection = calculateLeadCorrection(cars, spacing);
            updateLeadVanilla(lead, baseSpeed, safe, travelDir, plugin, leadCorrection);
        }

        maintainTrail(train);

        // === FOLLOWERS: TRAINCARTS-STYLE VELOCITY CORRECTION ===
        updateFollowers(train, plugin, baseSpeed, spacing);
    }

    /**
     * Update lead car with full kinematic control (TrainCarts-inspired approach)
     * Strategy:
     * 1. Calculate ideal position based on our physics
     * 2. Force minecart to that position (defeating passenger effects)
     * 3. Use aggressive enforcement to maintain control
     */
    private void updateLeadKinematic(Minecart lead, double baseSpeed, boolean safe,
            Vector travelDir, double timeFraction, Metro plugin, Vector spacingCorrection) {
        Location currentLoc = new Location(lead.getWorld(), leadX, leadY, leadZ);
        currentLoc = RailPathUtil.project(currentLoc);

        double configuredMax = Math.max(0.05, baseSpeed);
        lead.setMaxSpeed(configuredMax);
        KinematicLeadMotionPlanner.LeadMotionCommand command = KinematicLeadMotionPlanner.plan(
            currentLoc,
            lastLeadDirection,
            travelDir,
            lead.getVelocity().length(),
            baseSpeed,
            safe,
            plugin.getPhysicsLookaheadBlocks(),
            lead.getMaxSpeed(),
            spacingCorrection,
            timeFraction);
        KinematicLeadStateMath.LeadState leadState = command.leadState;
        leadX = leadState.x;
        leadY = leadState.y;
        leadZ = leadState.z;
        leadVx = leadState.vx;
        leadVy = leadState.vy;
        leadVz = leadState.vz;

        Vector leadVelocity = command.correctedVelocity.clone();
        snapToPosition(lead, leadState.location, leadVelocity, command.facingDirection, plugin, baseSpeed);
        lastLeadDirection = command.facingDirection.clone();

        trail.addPoint(leadX, leadY, leadZ, leadVx, leadVy, leadVz);
    }

    /**
     * Update lead car with vanilla physics (just clamp speed)
     */
    private void updateLeadVanilla(Minecart lead, double baseSpeed, boolean safe,
            Vector travelDir, Metro plugin, Vector spacingCorrection) {
        Location loc = lead.getLocation();
        Vector vel = lead.getVelocity();

        // Update our kinematic state from actual position
        leadX = loc.getX();
        leadY = loc.getY();
        leadZ = loc.getZ();

        double configuredMax = Math.max(0.05, baseSpeed);
        lead.setMaxSpeed(configuredMax);

        // Clamp speed if needed
        double currentSpeed = Math.sqrt(vel.getX() * vel.getX() + vel.getZ() * vel.getZ());
        LocationUtil.RailType railType = LocationUtil.getRailType(loc);
        double maxSpeed = LocationUtil.getSafeSpeedForRail(railType, baseSpeed, safe);

        if (currentSpeed > maxSpeed) {
            double scale = maxSpeed / currentSpeed;
            vel.multiply(scale);
            MinecartPhysicsUtil.forceVelocity(lead, vel, plugin);
        }

        Vector corrected = KinematicSpacingMath.applySpacingUpdate(vel, spacingCorrection, lead.getMaxSpeed());
        corrected = KinematicRailMotionMath.alignVelocityToRail(loc, corrected, corrected, lastLeadDirection);
        corrected = KinematicSpacingMath.clampVelocity(corrected, maxSpeed);
        MinecartPhysicsUtil.forceVelocity(lead, corrected, plugin);

        leadVx = corrected.getX();
        leadVy = corrected.getY();
        leadVz = corrected.getZ();

        Vector fallback = (travelDir != null && travelDir.lengthSquared() > 1.0e-6)
                ? travelDir.clone()
                : lastLeadDirection.clone();
        Vector facingDir = KinematicRailMotionMath.normalizeOr(corrected, fallback);
        lastLeadDirection = facingDir.clone();

        trail.addPoint(leadX, leadY, leadZ, leadVx, leadVy, leadVz);
    }

    /**
     * Update all follower cars using TrainCarts' exact approach:
     * 2. Snap to trail position (snapToPosition)
     * 
     * TrainCarts snapToPosition: directly sets position and preserves speed
     * magnitude
     */
    private void updateFollowers(TrainInstance train, Metro plugin, double baseSpeed,
            double spacing) {
        List<Minecart> cars = train.getConsist().getCars();
        int n = cars.size();
        if (n <= 1) {
            return;
        }

        // Process each follower cart
        for (int i = 1; i < n; i++) {
            Minecart car = cars.get(i);
            if (car == null || car.isDead()) {
                continue;
            }

            car.setGravity(false);
            car.setSlowWhenEmpty(false);
            car.setFlyingVelocityMod(new Vector(0, 0, 0));
            car.setMaxSpeed(Math.max(0.05, baseSpeed));

            // Step 2: Find target position on trail (spacing * i behind lead)
            double distanceBehind = spacing * i;
            KinematicTrailBuffer.TrailSample sample = trail.sampleState(distanceBehind, car.getWorld(), lastLeadDirection);

            if (sample == null) {
                KinematicFollowerController.FollowerCommand command = KinematicFollowerController.resolveWithoutTrail(
                        car.getLocation(),
                        car.getVelocity().clone(),
                        lastLeadDirection,
                        car.getMaxSpeed(),
                        baseSpeed);
                snapToPosition(car, command.targetPosition, command.velocity, command.facing, plugin, baseSpeed);
                continue;
            }

            KinematicFollowerController.FollowerCommand command = KinematicFollowerController.resolveWithSample(
                    sample,
                    car.getVelocity().clone(),
                    lastLeadDirection,
                    baseSpeed);
            snapToPosition(car, command.targetPosition, command.velocity, command.facing, plugin, baseSpeed);
        }
    }

    /**
     * Calculate spacing correction ONLY for the lead car.
     * This replaces the removed O(N) `calculateSpeedFactor` method.
     * 
     * Use O(1) check of just the first two carts.
     */
    private Vector calculateLeadCorrection(List<Minecart> cars, double spacing) {
        if (cars == null || cars.size() < 2) {
            return new Vector(0, 0, 0);
        }

        Minecart lead = cars.get(0);
        Minecart follower = cars.get(1);

        if (lead == null || lead.isDead() || follower == null || follower.isDead()) {
            return new Vector(0, 0, 0);
        }

        // Get actual positions
        Location leadLoc = RailPathUtil.project(lead.getLocation());
        Location followerLoc = RailPathUtil.project(follower.getLocation());

        // Calculate direction vector from lead to follower (backward)
        // Wait, the logic in calculateSpeedFactor was:
        // back=i (lead), front=i+1 (follower). Direction = front - back.
        // direction = follower - lead.

        Vector railDir = RailPathUtil.computeDirection(leadLoc, lastLeadDirection);
        Vector fallback = railDir != null && railDir.lengthSquared() > 1.0e-6
                ? railDir.normalize()
                : lastLeadDirection.clone().normalize();
        if (fallback.lengthSquared() < 1.0e-8) {
            fallback = new Vector(1, 0, 0);
        }
        return KinematicSpacingMath.calculateLeadCorrection(leadLoc, followerLoc, spacing, fallback);
    }

    private void maintainTrail(TrainInstance train) {
        if (train == null) {
            return;
        }
        trail.maintain(train.getConsist().getCars().size(), train.getService().getTrainSpacing());
    }

    /**
     * Snap minecart to position (TrainCarts snapToPosition - EXACT COPY)
     * TrainCarts implementation:
     * - entity.setPosition(position.posX, position.posY, position.posZ)
     * - entity.vel.set(position.motX * velocity, position.motY * velocity,
     * position.motZ * velocity)
     */
    private void snapToPosition(Minecart cart, Location loc, Vector vel, Vector facing, Metro plugin,
            double speedLimit) {
        // Disable vanilla physics, match TrainCarts order of operations
        cart.setGravity(false);
        cart.setSlowWhenEmpty(false);
        cart.setFlyingVelocityMod(new Vector(0, 0, 0));
        double configuredMax = Math.max(0.05,
                speedLimit > 0.0 ? speedLimit : (plugin != null ? plugin.getCartSpeed() : 0.4));
        cart.setMaxSpeed(configuredMax);

        float oldYaw = cart.getLocation().getYaw();
        KinematicSnapMath.SnapCommand command = KinematicSnapMath.prepare(loc, vel, facing, lastLeadDirection, oldYaw);

        boolean snappedDirect = MinecartNmsUtil.snap(cart, command.location, command.velocity, command.yaw, command.pitch);
        if (!snappedDirect) {
            cart.teleport(command.location);
            cart.setRotation(command.yaw, command.pitch);
        }

        if (plugin != null) {
            MinecartPhysicsUtil.forceVelocity(cart, command.velocity, plugin);
        } else {
            cart.setVelocity(command.velocity);
        }
    }

    private void seedTrailFromConsist(TrainInstance train) {
        List<Minecart> cars = train != null ? train.getConsist().getCars() : null;
        if (cars == null || cars.isEmpty()) {
            return;
        }

        Minecart lead = cars.get(0);
        if (lead == null || lead.isDead()) {
            return;
        }

        Location leadProjected = RailPathUtil.project(lead.getLocation());
        if (leadProjected == null) {
            leadProjected = lead.getLocation();
        }

        Vector leadVelocity = KinematicLeadBootstrap.resolveSeedVelocity(
                new Vector(leadVx, leadVy, leadVz),
                lastLeadDirection,
                train.getService().getCartSpeed());

        trail.seedFromConsist(cars, leadVelocity);
    }

    @Override
    public void onArrival(TrainInstance train, Stop atStop, long currentTick) {
        // Nothing needed
    }

    @Override
    public void cleanup(TrainInstance train) {
        trail.clear();
        initialized = false;
    }
}
