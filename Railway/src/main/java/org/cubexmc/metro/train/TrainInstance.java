package org.cubexmc.metro.train;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.cubexmc.metro.event.MetroTrainArrivalEvent;
import org.cubexmc.metro.event.MetroTrainDepartureEvent;
import org.cubexmc.metro.model.Line;
import org.cubexmc.metro.model.Stop;
import org.cubexmc.metro.model.EntityModelController;
import org.cubexmc.metro.manager.StopManager;
import org.cubexmc.metro.service.LineService;
import org.cubexmc.metro.estimation.TravelTimeEstimator;
import org.cubexmc.metro.util.LocationUtil;
import org.cubexmc.metro.util.MinecartPhysicsUtil;
import org.cubexmc.metro.physics.TrainPhysicsEngine;
import org.cubexmc.metro.physics.KinematicRailPhysics;
import org.cubexmc.metro.physics.ReactiveRailPhysics;
import org.cubexmc.metro.physics.LeashedRailPhysics;
import org.cubexmc.metro.control.TrainControlMode;
import org.cubexmc.metro.util.SchedulerUtil;
import org.cubexmc.metro.Metro;
import org.bukkit.entity.ArmorStand;

public class TrainInstance {

    public enum TrainState {
        WAITING, MOVING, TERMINATING, FINISHED
    }

    private final UUID id = UUID.randomUUID();
    private final LineService service;
    private final Line line;
    private final TrainConsist consist;
    private final TrainNavigator navigator;
    private final NamespacedKey key;

    private TrainState state;
    private long stateSinceTick;

    private boolean cleaned;
    private final int dwellTicks;
    private boolean readyToDepart;
    private long segmentStartTick = -1L;
    private final Set<UUID> segmentDepartPassengers = new HashSet<>();
    private long idleVirtualTicks = 0L;
    private int stalledTicks = 0;

    // Passenger tracking
    private final TrainPassengerRegistry passengerRegistry = new TrainPassengerRegistry();
    private TrainPhysicsEngine physicsEngine;

    // Chunk loading state
    private final Set<Long> forcedChunks = new HashSet<>();
    private long lastChunkUpdateTick = 0L;

    // Virtual train system
    private UUID virtualTrainId = null;

    public TrainInstance(LineService service, Line line, TrainConsist consist, List<String> orderedStopIds,
            long spawnTick, int dwellTicks) {
        this.service = Objects.requireNonNull(service, "service");
        this.line = Objects.requireNonNull(line, "line");
        this.consist = Objects.requireNonNull(consist, "consist");

        List<String> stopIdsList = new ArrayList<>(Objects.requireNonNull(orderedStopIds, "orderedStopIds"));
        if (stopIdsList.size() < 2) {
            throw new IllegalArgumentException("orderedStopIds must contain at least two stops");
        }
        this.navigator = new TrainNavigator(this, stopIdsList);

        this.key = this.service.getTrainKey();
        this.tagMinecarts();
        this.state = TrainState.WAITING;
        this.stateSinceTick = spawnTick;
        this.dwellTicks = Math.max(20, dwellTicks);
        this.physicsEngine = selectEngine();
        this.physicsEngine.init(this);
        attachEntityModels();
    }

    private void tagMinecarts() {
        for (Minecart cart : consist.getCars()) {
            cart.getPersistentDataContainer().set(key, PersistentDataType.STRING, id.toString());
        }
    }

    public UUID getId() {
        return id;
    }

    public TrainConsist getConsist() {
        return consist;
    }

    public LineService getService() {
        return service;
    }

    public Line getLine() {
        return line;
    }

    public TrainNavigator getNavigator() {
        return navigator;
    }

    public TrainPhysicsEngine getPhysicsEngine() {
        return physicsEngine;
    }

    public void refreshPhysicsEngine() {
        if (cleaned) {
            return;
        }
        physicsEngine.cleanup(this);
        physicsEngine = selectEngine();
        physicsEngine.init(this);
    }

    public void refreshEntityModels() {
        if (cleaned) {
            return;
        }
        for (Minecart cart : consist.getCars()) {
            removeEntityModel(cart);
        }
        attachEntityModels();
    }

    public String getTargetStopId() {
        return navigator.getTargetStopId();
    }

    public void setState(TrainState state, long tick) {
        this.state = state;
        this.stateSinceTick = tick;
    }

    public void setReadyToDepart(boolean ready) {
        this.readyToDepart = ready;
    }

    public void onSegmentStart(long tick) {
        this.segmentStartTick = tick;
        this.segmentDepartPassengers.clear();
        this.segmentDepartPassengers.addAll(passengerRegistry.snapshotPassengerIds());
    }

    public boolean isFinished() {
        return state == TrainState.FINISHED;
    }

    public boolean isWaiting() {
        return state == TrainState.WAITING;
    }

    public boolean isMoving() {
        return state == TrainState.MOVING;
    }

    public void update(long currentTick) {
        if (state != TrainState.MOVING) {
            idleVirtualTicks = 0L;
        }
        switch (state) {
            case WAITING:
                consist.zeroVelocity();
                if (!readyToDepart && currentTick - stateSinceTick >= dwellTicks) {
                    readyToDepart = true;
                }
                if (readyToDepart) {
                    navigator.attemptDeparture(currentTick);
                } else {
                    // Passenger-facing waiting UI is driven by MetroTrainArrivalEvent(DOCKED),
                    // matching the legacy Metro display pipeline.
                }
                break;
            case MOVING:
                navigator.checkArrival(currentTick);
                if (state != TrainState.MOVING)
                    break; // State might have changed to WAITING

                double leadSpeed = getLeadSpeed();
                int subSteps = computeSubSteps(leadSpeed);
                double stepFraction = subSteps <= 1 ? 1.0 : 1.0 / subSteps;
                for (int i = 0; i < subSteps; i++) {
                    maintainVelocity(stepFraction, leadSpeed);
                }
                // If head appears stuck for a short while, nudge it once
                if (leadSpeed < 1.0e-3) {
                    stalledTicks++;
                    if (stalledTicks >= 4) {
                        Minecart lead = consist.getLeadCar();
                        if (lead != null && !lead.isDead()) {
                            Vector td = navigator.getTravelDirection();
                            Vector dir = (td != null && td.lengthSquared() > 0)
                                    ? td.clone().normalize()
                                    : new Vector(1, 0, 0);
                            Vector v = dir.multiply(Math.max(0.1, service.getCartSpeed() * 0.5));
                            if (SchedulerUtil.isFolia()) {
                                SchedulerUtil.entityRun(service.getPlugin(), lead, (Runnable) () -> {
                                    lead.setVelocity(v);
                                }, 0L, -1L);
                            } else {
                                lead.setVelocity(v);
                            }
                        }
                        stalledTicks = 0;
                    }
                } else {
                    stalledTicks = 0;
                }
                maybeVirtualize(currentTick);
                // Passenger-facing journey UI is driven by MetroTrainDepartureEvent,
                // matching the legacy Metro display pipeline.
                break;
            case TERMINATING:
                consist.zeroVelocity();
                if (currentTick - stateSinceTick >= dwellTicks) {
                    finish();
                }
                break;
            case FINISHED:
            default:
                break;
        }

        // Update chunk loading (Global mode OR occupied Local mode)
        if (service.getPlugin().isChunkLoadingEnabled()) {
            updateChunkLoading(currentTick);
        }

        syncEntityModels();
    }

    private double getLeadSpeed() {
        Minecart lead = consist.getLeadCar();
        if (lead == null || lead.isDead()) {
            return 0.0;
        }
        return lead.getVelocity().length();
    }

    private int computeSubSteps(double leadSpeed) {
        int maxSubSteps = 12;
        if (leadSpeed <= 0.35)
            return 1;
        int steps = (int) Math.ceil(leadSpeed / 0.35);
        return Math.max(1, Math.min(steps, maxSubSteps));
    }

    private void maintainVelocity(double timeFraction, double leadSpeed) {
        Minecart lead = consist.getLeadCar();
        if (lead == null || lead.isDead()) {
            return;
        }
        physicsEngine.tick(this, timeFraction, -1L);
    }

    private boolean hasAnyPassengers() {
        if (passengerRegistry.hasOnlinePassengers()) {
            return true;
        }
        for (Minecart cart : consist.getCars()) {
            if (!cart.getPassengers().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void maybeVirtualize(long currentTick) {
        if (service.isGlobalMode()) {
            idleVirtualTicks = 0L;
            return;
        }

        Metro plugin = service.getPlugin();
        if (!plugin.isLocalVirtualizationEnabled()) {
            idleVirtualTicks = 0L;
            return;
        }

        // Prevent virtualization if ANY entity is on board. Entity-model passengers
        // ride the model entity, so the registry must count too.
        if (hasAnyPassengers()) {
            idleVirtualTicks = 0L;
            return;
        }

        if (navigator.getTargetIndex() < 0 || navigator.getTargetIndex() >= navigator.getStopIds().size()) {
            idleVirtualTicks = 0L;
            return;
        }

        double radius = plugin.getLocalActivationRadius();
        double radiusSq = radius * radius;
        int lookahead = plugin.getLocalVirtualLookaheadStops();

        StopManager stopManager = service.getStopManager();
        boolean demandAhead = false;
        for (int i = 0; i < lookahead && (navigator.getTargetIndex() + i) < navigator.getStopIds().size(); i++) {
            Stop candidate = stopManager.getStop(navigator.getStopIds().get(navigator.getTargetIndex() + i));
            if (candidate != null && plugin.isPlayerWithinStopRadius(candidate, radiusSq)) {
                demandAhead = true;
                break;
            }
        }

        if (demandAhead) {
            idleVirtualTicks = 0L;
            return;
        }

        idleVirtualTicks++;
        if (idleVirtualTicks >= plugin.getLocalVirtualIdleTicks()) {
            service.virtualizeBackToPool(this, currentTick);
            service.markVirtualizedTrain(currentTick);
            finishImmediately();
        }
    }

    // legacy trail helpers removed; physics engine owns path following

    public void applyInitialBoost(Vector direction) {
        Vector launchDir = direction != null ? direction.clone() : new Vector(0, 0, 1);
        if (launchDir.lengthSquared() == 0) {
            launchDir = new Vector(0, 0, 1);
        }
        launchDir.normalize();

        double initialSpeed = service.getCartSpeed();

        List<Minecart> cars = consist.getCars();
        for (Minecart cart : cars) {
            if (cart == null || cart.isDead()) {
                continue;
            }
            Vector v = launchDir.clone().multiply(initialSpeed);
            if (SchedulerUtil.isFolia()) {
                SchedulerUtil.entityRun(service.getPlugin(), cart, (Runnable) () -> {
                    cart.setVelocity(v);
                }, 0L, -1L);
            } else {
                cart.setVelocity(v);
            }
        }
    }

    public void handleArrival(Stop stop, long currentTick) {
        if (state != TrainState.MOVING) {
            return;
        }

        String targetId = navigator.getTargetStopId();
        if (targetId == null || !targetId.equals(stop.getId())) {
            return;
        }

        consist.zeroVelocity();
        if (navigator.getSectionKey() != null) {
            service.getBlockSectionManager().leave(navigator.getSectionKey());
            navigator.setSectionKey(null);
        }
        navigator.setTravelDirection(null);
        TrainNavigatorDecisions.ArrivalDecision arrival = TrainNavigatorDecisions.resolveArrival(
                navigator.getStopIds(),
                navigator.getTargetIndex(),
                stop.getId(),
                service.isLoopLine());
        if (!arrival.valid) {
            return;
        }

        state = arrival.nextState;
        stateSinceTick = currentTick;
        readyToDepart = false;
        navigator.setCurrentIndex(arrival.currentIndex);
        navigator.setTargetIndex(arrival.targetIndex);

        physicsEngine.onArrival(this, stop, currentTick);
        publishArrivalEvents(stop, arrival.terminal);

        // Record travel time sample for estimator
        tryRecordTravelTimeSample(currentTick);
    }

    private void finish() {
        consist.zeroVelocity();
        releaseRoutingReservation();
        releaseAllForcedChunks();
        state = TrainState.FINISHED;
    }

    public void finishImmediately() {
        consist.zeroVelocity();
        releaseRoutingReservation();
        releaseAllForcedChunks();
        state = TrainState.FINISHED;
    }

    public void cleanup() {
        if (cleaned) {
            return;
        }
        cleaned = true;
        physicsEngine.cleanup(this);
        consist.zeroVelocity();
        releaseRoutingReservation();
        releaseAllForcedChunks();

        for (Minecart cart : consist.getCars()) {
            cart.getPersistentDataContainer().remove(key);
            removeEntityModel(cart);
            if (!cart.isDead()) {
                for (Entity passenger : new ArrayList<>(cart.getPassengers())) {
                    if (passenger instanceof Player) {
                        service.getPlugin().getScoreboardManager().clearPlayerDisplay((Player) passenger);
                    }
                    passenger.leaveVehicle();
                }
                cart.remove();
            }
        }
        resetTransientRuntimeState();
        consist.clear();
    }

    // Helpers for local strategy ETA
    public String getCurrentFromStopId() {
        if (navigator.getTargetIndex() >= 0 && navigator.getCurrentIndex() >= 0
                && navigator.getCurrentIndex() < navigator.getStopIds().size()) {
            return navigator.getStopIds().get(navigator.getCurrentIndex());
        }
        return null;
    }

    public String getCurrentToStopId() {
        if (navigator.getTargetIndex() >= 0 && navigator.getTargetIndex() < navigator.getStopIds().size()) {
            return navigator.getStopIds().get(navigator.getTargetIndex());
        }
        return null;
    }

    public double getSegmentElapsedSeconds(long currentTick) {
        if (state == TrainState.MOVING) {
            return Math.max(0.0, (currentTick - stateSinceTick) / 20.0);
        }
        return 0.0;
    }

    public double estimateEtaSecondsToStop(String stopId, long currentTick, TravelTimeEstimator estimator) {
        List<String> stopIds = navigator.getStopIds();
        return TrainStateMath.estimateEtaSecondsToStop(
                state,
                stopIds,
                navigator.getCurrentIndex(),
                navigator.getTargetIndex(),
                service.isLoopLine(),
                stopId,
                getSegmentElapsedSeconds(currentTick),
                (fromStopId, toStopId) -> estimator.estimateSeconds(service.getLineId(), fromStopId, toStopId));
    }

    public boolean isLead(Minecart cart) {
        Minecart lead = consist.getLeadCar();
        return lead != null && lead.getUniqueId().equals(cart.getUniqueId());
    }

    public Vector getTravelDirection() {
        return navigator.getTravelDirection();
    }

    private TrainPhysicsEngine selectEngine() {
        // Determine effective mode: line override or global default
        TrainControlMode mode = line.getControlMode();
        if (mode == null) {
            mode = TrainControlMode.from(service.getPlugin().config().getControlMode(),
                    TrainControlMode.KINEMATIC);
        }
        switch (mode) {
            case REACTIVE:
                return new ReactiveRailPhysics();
            case LEASHED:
                return new LeashedRailPhysics();
            case KINEMATIC:
            default:
                return new KinematicRailPhysics();
        }
    }

    private void attachEntityModels() {
        Metro plugin = service.getPlugin();
        EntityModelController controller = plugin.getEntityModelController();
        if (controller == null) {
            return;
        }
        if (!shouldUseEntityModels(plugin)) {
            return;
        }
        String entityTypeOverride = EntityModelController.usesVisualEntity(line.getEntityType())
                ? line.getEntityType()
                : null;
        for (Minecart cart : consist.getCars()) {
            controller.attachModel(cart, entityTypeOverride);
        }
    }

    private void syncEntityModels() {
        Metro plugin = service.getPlugin();
        EntityModelController controller = plugin.getEntityModelController();
        if (controller == null) {
            return;
        }
        for (Minecart cart : consist.getCars()) {
            controller.syncPosition(cart);
        }
    }

    private boolean shouldUseEntityModels(Metro plugin) {
        if (EntityModelController.usesVisualEntity(line.getEntityType())) {
            return true;
        }
        return !line.hasEntityTypeOverride() && plugin.getConfig().getBoolean("entity-model.enabled", false);
    }

    private void removeEntityModel(Minecart cart) {
        EntityModelController controller = service.getPlugin().getEntityModelController();
        if (controller != null) {
            controller.removeModel(cart);
        }
    }

    // Passenger management methods

    public void addPassenger(org.bukkit.entity.HumanEntity player, Minecart cart) {
        if (player != null && cart != null && consist.getCars().contains(cart)) {
            boolean added = passengerRegistry.add(player, cart);
            updatePassengerScoreboard(player);
            if (added && state == TrainState.WAITING) {
                publishDockedEventFor(player, cart, currentStop(), isTerminalStop());
            }
        }
    }

    public void removePassenger(org.bukkit.entity.HumanEntity player) {
        passengerRegistry.remove(player);
    }

    public List<org.bukkit.entity.HumanEntity> getPassengers() {
        return passengerRegistry.onlinePassengers();
    }

    public boolean hasPassengers() {
        return passengerRegistry.hasOnlinePassengers();
    }

    public boolean isPassenger(org.bukkit.entity.HumanEntity player) {
        return passengerRegistry.contains(player);
    }

    public Minecart getPassengerCart(org.bukkit.entity.Entity player) {
        if (player == null || player.getVehicle() == null) {
            return player instanceof org.bukkit.entity.HumanEntity human
                    ? passengerRegistry.cartFor(human)
                    : null;
        }

        Entity vehicle = player.getVehicle();
        if (vehicle instanceof Minecart && consist.getCars().contains((Minecart) vehicle)) {
            return (Minecart) vehicle;
        }

        return player instanceof org.bukkit.entity.HumanEntity human
                ? passengerRegistry.cartFor(human)
                : null;
    }

    public String getCurrentStopId() {
        List<String> stopIds = navigator.getStopIds();
        int currentIndex = navigator.getCurrentIndex();
        if (currentIndex >= 0 && currentIndex < stopIds.size()) {
            return stopIds.get(currentIndex);
        }
        return null;
    }

    public int getRemainingDwellTicks(long currentTick) {
        if (state != TrainState.WAITING) {
            return 0;
        }
        long elapsed = Math.max(0L, currentTick - stateSinceTick);
        return Math.max(0, (int) (dwellTicks - elapsed));
    }

    void publishDepartureEvents(Stop currentStop, Stop nextStop) {
        if (currentStop == null || nextStop == null) {
            return;
        }
        for (org.bukkit.entity.HumanEntity passenger : getPassengers()) {
            if (passenger instanceof Player player) {
                Minecart passengerCart = passengerRegistry.cartFor(passenger);
                if (passengerCart == null) {
                    passengerCart = consist.getLeadCar();
                }
                Bukkit.getPluginManager().callEvent(new MetroTrainDepartureEvent(
                        passengerCart, player, line, currentStop, nextStop));
            }
        }
    }

    void publishArrivalEvents(Stop currentStop, boolean terminal) {
        if (currentStop == null) {
            return;
        }
        for (org.bukkit.entity.HumanEntity passenger : getPassengers()) {
            Minecart passengerCart = passengerRegistry.cartFor(passenger);
            publishEnteringEventFor(passenger, passengerCart, currentStop, terminal);
            publishDockedEventFor(passenger, passengerCart, currentStop, terminal);
        }
    }

    private void publishEnteringEventFor(org.bukkit.entity.HumanEntity passenger, Minecart cart, Stop stop,
            boolean terminal) {
        if (!(passenger instanceof Player player) || stop == null) {
            return;
        }
        Minecart eventCart = cart != null ? cart : consist.getLeadCar();
        Bukkit.getPluginManager().callEvent(new MetroTrainArrivalEvent(
                eventCart, player, line, stop, terminal, MetroTrainArrivalEvent.ArrivalType.ENTERING));
    }

    private void publishDockedEventFor(org.bukkit.entity.HumanEntity passenger, Minecart cart, Stop stop,
            boolean terminal) {
        if (!(passenger instanceof Player player) || stop == null) {
            return;
        }
        Minecart eventCart = cart != null ? cart : consist.getLeadCar();
        Bukkit.getPluginManager().callEvent(new MetroTrainArrivalEvent(
                eventCart, player, line, stop, terminal, MetroTrainArrivalEvent.ArrivalType.DOCKED));
    }

    private Stop currentStop() {
        String currentStopId = getCurrentStopId();
        return currentStopId != null ? service.getStopManager().getStop(currentStopId) : null;
    }

    private boolean isTerminalStop() {
        List<String> stopIds = navigator.getStopIds();
        int currentIndex = navigator.getCurrentIndex();
        return currentIndex >= stopIds.size() - 1 && !service.isLoopLine();
    }

    private void updatePassengerScoreboard(org.bukkit.entity.HumanEntity passenger) {
        if (!(passenger instanceof Player player) || service.getPlugin().getScoreboardManager() == null) {
            return;
        }
        String currentStopId = getCurrentStopId();
        if (state == TrainState.WAITING && currentStopId != null) {
            service.getPlugin().getScoreboardManager().updateEnteringStopScoreboard(player, line, currentStopId);
        } else if (state == TrainState.TERMINATING && currentStopId != null) {
            service.getPlugin().getScoreboardManager().updateTerminalScoreboard(player, line, currentStopId);
        } else {
            service.getPlugin().getScoreboardManager().updateTravelingScoreboard(player, line, getTargetStopId());
        }
    }

    public void tryRecordTravelTimeSample(long currentTick) {
        if (segmentStartTick < 0 || navigator.getTargetIndex() < 0
                || navigator.getTargetIndex() >= navigator.getStopIds().size()) {
            segmentStartTick = -1L;
            segmentDepartPassengers.clear();
            return;
        }
        String fromId = navigator.getStopIds().get(Math.max(0, navigator.getCurrentIndex() - 1));
        String toId = navigator.getStopIds().get(navigator.getCurrentIndex());
        double durationSeconds = Math.max(0.0, (currentTick - segmentStartTick) / 20.0);

        boolean hasCompletePassenger = false;
        for (UUID uid : segmentDepartPassengers) {
            if (passengerRegistry.contains(uid)) {
                hasCompletePassenger = true;
                break;
            }
        }

        double weight;
        if (hasCompletePassenger) {
            weight = 1.0;
        } else if (service.getPlugin().isUseUnboardedSamples()) {
            weight = service.getPlugin().getUnboardedSampleWeight();
        } else {
            weight = 0.0;
        }

        if (weight > 0.0) {
            service.getPlugin().getTravelTimeEstimator().record(
                    service.getLineId(), fromId, toId, durationSeconds, weight);
        }

        // Reset segment tracking
        segmentStartTick = -1L;
        segmentDepartPassengers.clear();
    }

    // ===== Chunk loading (global mode) =====
    private void updateChunkLoading(long currentTick) {
        // Throttle updates
        Metro plugin = service.getPlugin();
        int interval = plugin.getChunkLoadingUpdateIntervalTicks();
        if (interval > 1 && (currentTick - lastChunkUpdateTick) < interval) {
            return;
        }
        lastChunkUpdateTick = currentTick;

        // Enable chunk loading if Global Mode OR if we have passengers (even in Local
        // Mode)
        // This ensures entities can travel autonomously without unloading
        boolean shouldLoad = TrainRuntimeDecisions.shouldKeepChunksLoaded(
                service.getPlugin().isChunkLoadingEnabled(),
                service.isGlobalMode(),
                hasAnyPassengers(),
                plugin.isChunkLoadingOnlyWhenMoving(),
                state);

        if (!shouldLoad) {
            releaseAllForcedChunks();
            return;
        }

        List<Minecart> cars = consist.getCars();
        if (cars.isEmpty()) {
            releaseAllForcedChunks();
            return;
        }

        int radius = Math.max(0, plugin.getChunkLoadingRadius());
        int fwd = Math.max(0, plugin.getForwardPreloadRadius());

        Set<Long> desired = new HashSet<>();
        // Moving window around each car
        for (Minecart cart : cars) {
            if (cart == null || cart.isDead())
                continue;
            Location loc = cart.getLocation();
            int cx = loc.getBlockX() >> 4;
            int cz = loc.getBlockZ() >> 4;
            addChunkSquare(desired, cx, cz, radius);
        }

        // Forward preloading ahead of lead car
        Minecart lead = consist.getLeadCar();
        if (lead != null && !lead.isDead() && fwd > 0) {
            Vector dir = (navigator.getTravelDirection() != null && navigator.getTravelDirection().lengthSquared() > 0)
                    ? navigator.getTravelDirection().clone().setY(0)
                    : lead.getVelocity().clone().setY(0);
            if (dir.lengthSquared() > 0.0001) {
                dir.normalize();
                Location base = lead.getLocation();
                for (int step = 1; step <= fwd; step++) {
                    Location ahead = base.clone().add(dir.clone().multiply(16 * step));
                    int acx = ahead.getBlockX() >> 4;
                    int acz = ahead.getBlockZ() >> 4;
                    addChunkSquare(desired, acx, acz, radius);
                }
            }
        }

        // Apply diff: force new, release old
        // Add new
        for (Long key : desired) {
            if (!forcedChunks.contains(key)) {
                int cx = (int) (key >> 32);
                int cz = (int) (key & 0xffffffffL);
                forceChunk(cx, cz, true);
            }
        }
        // Remove old
        for (Long key : new HashSet<>(forcedChunks)) {
            if (!desired.contains(key)) {
                int cx = (int) (key >> 32);
                int cz = (int) (key & 0xffffffffL);
                forceChunk(cx, cz, false);
            }
        }

        // Update current set
        forcedChunks.clear();
        forcedChunks.addAll(desired);
    }

    private void addChunkSquare(Set<Long> out, int cx, int cz, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                out.add(packChunk(cx + dx, cz + dz));
            }
        }
    }

    private long packChunk(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xffffffffL);
    }

    private void forceChunk(int cx, int cz, boolean forced) {
        World world = getLeadWorld();
        if (world == null)
            return;
        // Use region scheduler for Folia safety; pick a location in the target chunk
        Location center = new Location(world, (cx << 4) + 8, Math.max(world.getMinHeight() + 1, 64), (cz << 4) + 8);
        SchedulerUtil.regionRun(service.getPlugin(), center, () -> {
            try {
                world.setChunkForceLoaded(cx, cz, forced);
            } catch (Throwable t) {
                service.getPlugin().getLogger()
                        .warning("Failed to set chunk force-loaded at " + cx + "," + cz + ": " + t.getMessage());
            }
        }, 0L, -1L);
    }

    private World getLeadWorld() {
        Minecart lead = consist.getLeadCar();
        return (lead != null) ? lead.getWorld() : null;
    }

    private void releaseAllForcedChunks() {
        if (forcedChunks.isEmpty())
            return;
        World world = getLeadWorld();
        if (world == null) {
            forcedChunks.clear();
            return;
        }
        for (Long key : new HashSet<>(forcedChunks)) {
            int cx = (int) (key >> 32);
            int cz = (int) (key & 0xffffffffL);
            forceChunk(cx, cz, false);
        }
        forcedChunks.clear();
    }

    private void releaseRoutingReservation() {
        if (navigator.getSectionKey() != null) {
            service.getBlockSectionManager().leave(navigator.getSectionKey());
            navigator.setSectionKey(null);
        }
        navigator.setTravelDirection(null);
    }

    private void resetTransientRuntimeState() {
        readyToDepart = false;
        segmentStartTick = -1L;
        segmentDepartPassengers.clear();
        passengerRegistry.clear();
        idleVirtualTicks = 0L;
        stalledTicks = 0;
        virtualTrainId = null;
    }

    public void prepareForBoarding(Minecart cart) {
        // No-op, conductor removed
    }

    // ===== Virtual Train System Methods =====

    public void setVirtualTrainId(UUID id) {
        this.virtualTrainId = id;
    }

    public UUID getVirtualTrainId() {
        return virtualTrainId;
    }

    /**
     * Force the train into WAITING state at a specific stop.
     * Used when spawning directly at the target stop.
     */
    public void forceWaitingState(int stopIndex, long currentTick) {
        navigator.setCurrentIndex(Math.max(0, Math.min(stopIndex, navigator.getStopIds().size() - 1)));
        navigator.setTargetIndex(-1);
        this.state = TrainState.WAITING;
        this.stateSinceTick = currentTick;
        this.readyToDepart = false;
        navigator.setTravelDirection(null);
        consist.zeroVelocity();
    }

    /**
     * Force the train into MOVING state treating it as arriving at
     * navigator.getTargetIndex().
     */
    public void forceArrivingState(int targetIndex, long currentTick) {
        forceArrivingState(targetIndex, currentTick, null);
    }

    /**
     * Force the train into MOVING state with explicit direction.
     */
    public void forceArrivingState(int targetIndex, long currentTick, Vector explicitDirection) {
        navigator.setTargetIndex(Math.max(0, Math.min(targetIndex, navigator.getStopIds().size() - 1)));
        navigator.setCurrentIndex(navigator.getTargetIndex());
        this.state = TrainState.MOVING;
        this.stateSinceTick = currentTick;
        this.readyToDepart = false;

        if (explicitDirection != null && explicitDirection.lengthSquared() > 1e-6) {
            navigator.setTravelDirection(explicitDirection.clone().normalize());
        } else {
            Stop stop = service.getStopManager().getStop(navigator.getStopIds().get(navigator.getTargetIndex()));
            if (stop != null) {
                navigator.setTravelDirection(LocationUtil.vectorFromYaw(stop.getLaunchYaw()));
            }
        }

        if (navigator.getTravelDirection() == null || navigator.getTravelDirection().lengthSquared() < 1e-6) {
            navigator.setTravelDirection(new Vector(0, 0, 1));
        }

        applyInitialBoost(navigator.getTravelDirection());
    }

    /**
     * Adjust the starting index for a train spawned mid-route.
     * The train will depart from this index.
     */
    public void adjustStartIndex(int startIndex, long currentTick) {
        this.navigator.setCurrentIndex(Math.max(0, Math.min(startIndex, navigator.getStopIds().size() - 1)));
        this.navigator.setTargetIndex(-1);
        this.state = TrainState.WAITING;
        this.stateSinceTick = currentTick;
        // Set ready to depart immediately if spawned mid-route
        this.readyToDepart = true;
    }

    /**
     * Get current state for virtualization.
     * Returns [navigator.getCurrentIndex(), navigator.getTargetIndex(),
     * progress, isWaiting]
     */
    public VirtualizationState getVirtualizationState(long currentTick) {
        List<String> stopIds = navigator.getStopIds();
        boolean isWaiting = TrainStateMath.isVirtualWaitingState(state);
        double progress = TrainStateMath.estimateVirtualProgress(
                state,
                navigator.getCurrentIndex(),
                navigator.getTargetIndex(),
                stopIds,
                getSegmentElapsedSeconds(currentTick),
                (fromStopId, toStopId) -> service.getPlugin().getTravelTimeEstimator()
                        .estimateSeconds(service.getLineId(), fromStopId, toStopId));

        return new VirtualizationState(navigator.getCurrentIndex(), navigator.getTargetIndex(), progress, isWaiting);
    }

    /**
     * State data for virtualizing a physical train back to virtual.
     */
    public static class VirtualizationState {
        public final int currentIndex;
        public final int targetIndex;
        public final double progress;
        public final boolean isWaiting;

        public VirtualizationState(int currentIndex, int targetIndex, double progress, boolean isWaiting) {
            this.currentIndex = currentIndex;
            this.targetIndex = targetIndex;
            this.progress = progress;
            this.isWaiting = isWaiting;
        }
    }
}
