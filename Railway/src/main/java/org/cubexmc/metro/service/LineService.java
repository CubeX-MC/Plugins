package org.cubexmc.metro.service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.util.Vector;
import org.cubexmc.metro.Metro;
import org.cubexmc.metro.manager.StopManager;
import org.cubexmc.metro.model.EntityModelController;
import org.cubexmc.metro.model.Line;
import org.cubexmc.metro.model.Stop;
import org.cubexmc.metro.service.strategy.GlobalDispatchStrategy;
import org.cubexmc.metro.service.strategy.LocalDispatchStrategy;
import org.cubexmc.metro.train.TrainInstance;
import org.cubexmc.metro.util.LineTopologyUtil;
import org.cubexmc.metro.util.SchedulerUtil;

public class LineService {

    private final Metro plugin;
    private final LineServiceManager manager;
    private final String lineId;
    private final DispatchStrategy dispatchStrategy;
    private final TrainSpawner spawner;
    private final List<TrainInstance> activeTrains = new ArrayList<>();
    private final Set<TrainInstance> pendingRemoval = new LinkedHashSet<>();

    private int headwaySeconds;
    private int dwellTicks;
    private int trainCars;
    private long lastDepartureTick = -1L;
    private long totalTicks = 0L;
    private long totalTrainUpdates = 0L;
    private long totalSpawns = 0L;

    public LineService(Metro plugin, LineServiceManager manager, String lineId, int headwaySeconds, int dwellTicks,
            int trainCars, DispatchStrategy dispatchStrategy) {
        this.plugin = plugin;
        this.manager = manager;
        this.lineId = lineId;
        this.headwaySeconds = headwaySeconds;
        this.dwellTicks = dwellTicks;
        this.trainCars = Math.max(1, trainCars);
        this.dispatchStrategy = dispatchStrategy;
        this.spawner = new TrainSpawner(plugin, this);
    }

    public void tick() {
        totalTicks++;
        long currentTick = SchedulerUtil.getCurrentTick();
        if (dispatchStrategy != null) {
            dispatchStrategy.tick(this, currentTick);
        }
        updateActiveTrains(currentTick);
    }

    public void shutdown() {
        for (TrainInstance train : activeTrains) {
            queueTrainRemoval(train);
        }
        flushPendingTrainRemoval();
    }

    public void refreshStops() {
        Line line = getLine();
        if (line == null) return;
        List<String> newStops = line.getOrderedStopIds();

        long currentTick = SchedulerUtil.getCurrentTick();
        if (dispatchStrategy != null) {
            dispatchStrategy.refreshTopology(this, newStops, currentTick);
        }

        if (activeTrains.isEmpty()) return;

        LocalDispatchStrategy localDispatch = dispatchStrategy instanceof LocalDispatchStrategy local
                ? local : null;
        int retiredTrains = 0;
        int releasedVirtualTrains = 0;
        for (TrainInstance train : new ArrayList<>(activeTrains)) {
            if (localDispatch != null && train.getVirtualTrainId() != null) {
                localDispatch.releaseMaterializedTrain(train.getVirtualTrainId());
                releasedVirtualTrains++;
            }
            train.finishImmediately();
            queueTrainRemoval(train);
            retiredTrains++;
        }
        flushPendingTrainRemoval();

        plugin.getLogger().info("Refreshed topology for line " + lineId
                + "; retired " + retiredTrains + " active train(s)"
                + (releasedVirtualTrains > 0 ? " and released " + releasedVirtualTrains + " virtual train(s)" : "")
                + " to prevent stop-index desync.");
    }

    // ===== Delegated train management for TrainSpawner =====

    /**
     * Register a newly spawned train. Called by {@link TrainSpawner}.
     */
    public void addTrain(TrainInstance train, long currentTick) {
        activeTrains.add(train);
        totalSpawns++;
        manager.registerTrain(train);
    }

    // ===== Spawn methods (delegated to TrainSpawner) =====

    public void spawnTrain(long currentTick) {
        spawner.spawnTrainAtFirstStop(currentTick);
    }

    public TrainInstance spawnTrainForVirtual(long currentTick, int fromStopIndex, int toStopIndex,
            double progress, UUID virtualTrainId, String targetStopId) {
        return spawner.spawnTrainForVirtual(currentTick, fromStopIndex, toStopIndex,
                progress, virtualTrainId, targetStopId);
    }

    public void requestStop(String stopId, long currentTick) {
        if (dispatchStrategy != null) {
            dispatchStrategy.requestStop(this, stopId, currentTick);
        }
    }

    // ===== Train lifecycle =====

    private void updateActiveTrains(long currentTick) {
        Iterator<TrainInstance> iterator = activeTrains.iterator();
        while (iterator.hasNext()) {
            TrainInstance train = iterator.next();
            totalTrainUpdates++;
            train.update(currentTick);
            if (train.isFinished()) {
                queueTrainRemoval(train);
            }
        }
        flushPendingTrainRemoval();
    }

    public void handleTrainDerail(TrainInstance train) {
        if (train == null) return;
        train.finishImmediately();
        queueTrainRemoval(train);
    }

    public void virtualizeBackToPool(TrainInstance train, long currentTick) {
        if (dispatchStrategy instanceof LocalDispatchStrategy localDispatch) {
            localDispatch.returnTrainToPool(train, currentTick);
        }
    }

    private void queueTrainRemoval(TrainInstance train) {
        if (train != null) {
            pendingRemoval.add(train);
        }
    }

    private void flushPendingTrainRemoval() {
        if (pendingRemoval.isEmpty()) return;
        long currentTick = SchedulerUtil.getCurrentTick();
        for (TrainInstance train : pendingRemoval) {
            restoreVirtualAuthorityIfNeeded(train, currentTick);
            manager.unregisterTrain(train);
            train.cleanup();
            activeTrains.remove(train);
        }
        pendingRemoval.clear();
    }

    private void restoreVirtualAuthorityIfNeeded(TrainInstance train, long currentTick) {
        if (!(dispatchStrategy instanceof LocalDispatchStrategy localDispatch)) return;
        if (train == null || train.getVirtualTrainId() == null) return;
        if (!localDispatch.isMaterializedTrain(train.getVirtualTrainId())) return;
        localDispatch.returnTrainToPool(train, currentTick);
    }

    // ===== ETA / Scheduling =====

    public int estimateNextEtaSeconds(long currentTick) {
        int headwayTicks = getHeadwayTicks();
        if (lastDepartureTick < 0) {
            long mod = currentTick % headwayTicks;
            long etaTicks = (mod == 0) ? headwayTicks : (headwayTicks - mod);
            return (int) Math.ceil(etaTicks / 20.0);
        }
        long elapsed = currentTick - lastDepartureTick;
        if (elapsed < 0) elapsed = 0;
        long remaining = headwayTicks - (elapsed % headwayTicks);
        if (remaining == headwayTicks) remaining = 0;
        return (int) Math.ceil(remaining / 20.0);
    }

    public int estimateNextEtaSeconds(long currentTick, String stopId) {
        Integer virtualEta = estimateVirtualEtaSeconds(stopId);
        if (virtualEta != null) return virtualEta;

        double departureEtaSeconds = estimateNextEtaSeconds(currentTick);
        Line line = getLine();
        if (line == null || stopId == null || stopId.isEmpty()) {
            return (int) Math.ceil(departureEtaSeconds);
        }

        List<String> stopIds = line.getOrderedStopIds();
        double etaSeconds = ServiceEtaCalculator.estimateScheduledEtaSeconds(
                stopIds, stopId, dwellTicks, departureEtaSeconds,
                (fromStopId, toStopId) -> plugin.getTravelTimeEstimator().estimateSeconds(lineId, fromStopId, toStopId));
        return (int) Math.ceil(etaSeconds);
    }

    private Integer estimateVirtualEtaSeconds(String stopId) {
        if (!(dispatchStrategy instanceof LocalDispatchStrategy localDispatch)) return null;
        if (stopId == null || stopId.isEmpty()) return null;

        Line line = getLine();
        org.cubexmc.metro.service.virtual.VirtualTrainPool pool = localDispatch.getPool();
        if (line == null || pool == null) return null;

        List<String> stopIds = line.getOrderedStopIds();
        int stopIndex = stopIds.indexOf(stopId);
        if (stopIndex < 0) return null;

        org.cubexmc.metro.service.virtual.VirtualTrain candidate = pool.findBestCandidateForStop(
                stopId, stopIds, Double.POSITIVE_INFINITY,
                plugin.getTravelTimeEstimator(), SchedulerUtil.getCurrentTick());
        if (candidate == null) return null;

        double etaSeconds = candidate.estimateEtaToStop(stopIndex, plugin.getTravelTimeEstimator(), SchedulerUtil.getCurrentTick());
        if (!Double.isFinite(etaSeconds)) return null;
        return Math.max(0, (int) Math.round(etaSeconds));
    }

    public boolean isDepartureWindow(long currentTick) {
        return lastDepartureTick < 0 || currentTick - lastDepartureTick >= getHeadwayTicks();
    }

    public void markDeparture(long currentTick) {
        lastDepartureTick = currentTick;
    }

    public boolean tryMarkDeparture(long currentTick) {
        if (isDepartureWindow(currentTick)) {
            markDeparture(currentTick);
            return true;
        }
        return false;
    }

    public void markVirtualizedTrain(long currentTick) {
        if (isGlobalMode()) return;
        lastDepartureTick = Math.max(0L, currentTick - getHeadwayTicks());
    }

    // ===== Travel direction =====

    public Vector computeTravelDirection(Stop fromStop, Stop toStop) {
        if (fromStop == null) return new Vector(0, 0, 1);
        Vector direction = org.cubexmc.metro.util.LocationUtil.vectorFromYaw(fromStop.getLaunchYaw());
        return (direction != null && direction.lengthSquared() > 0) ? direction.normalize() : new Vector(0, 0, 1);
    }

    // ===== Getters / Accessors =====

    public String getLineId() { return lineId; }
    public int getHeadwaySeconds() { return headwaySeconds; }
    public int getHeadwayTicks() { return Math.max(20, headwaySeconds * 20); }
    public int getDwellTicks() { return dwellTicks; }
    public int getTrainCars() { return trainCars; }
    public Metro getPlugin() { return plugin; }
    public NamespacedKey getTrainKey() { return manager.getTrainKey(); }
    public BlockSectionManager getBlockSectionManager() { return manager.getBlockSectionManager(); }
    public StopManager getStopManager() { return plugin.getStopManager(); }
    public Line getLine() { return plugin.getLineManager().getLine(lineId); }
    public List<TrainInstance> getActiveTrains() { return new ArrayList<>(activeTrains); }

    public double getCartSpeed() {
        Line line = getLine();
        if (line != null) {
            double lineSpeed = line.getMaxSpeed();
            if (lineSpeed > 0) return lineSpeed;
        }
        return plugin.getCartSpeed();
    }

    public double getTrainSpacing() {
        Line line = getLine();
        String entityType = line != null ? line.getEntityType() : EntityModelController.MINECART_ENTITY_TYPE;
        if (line != null && !line.hasEntityTypeOverride()
                && plugin.getConfig().getBoolean("entity-model.enabled", false)
                && plugin.getEntityModelController() != null) {
            entityType = plugin.getEntityModelController().getDefaultEntityTypeRaw();
        }
        if (plugin.getEntityModelController() != null) {
            return plugin.getEntityModelController().getRecommendedSpacing(entityType, plugin.getTrainSpacing());
        }
        return EntityModelController.recommendedSpacing(entityType, plugin.getTrainSpacing());
    }
    public String buildSectionKey(String fromStopId, String toStopId) { return lineId + ":" + fromStopId + "->" + toStopId; }
    public boolean isGlobalMode() { return dispatchStrategy instanceof GlobalDispatchStrategy; }

    public boolean isLoopLine() {
        Line line = getLine();
        return line != null && LineTopologyUtil.isLoop(line.getOrderedStopIds());
    }

    public void setHeadwaySeconds(int headwaySeconds) { this.headwaySeconds = headwaySeconds; }
    public void setDwellTicks(int dwellTicks) { this.dwellTicks = dwellTicks; }
    public void setTrainCars(int trainCars) { this.trainCars = Math.max(1, trainCars); }

    public void refreshPhysicsEngines() {
        for (TrainInstance train : activeTrains) {
            train.refreshPhysicsEngine();
        }
    }

    public void refreshEntityModels() {
        for (TrainInstance train : activeTrains) {
            train.refreshEntityModels();
        }
    }

    public long getTotalTicks() { return totalTicks; }
    public long getTotalTrainUpdates() { return totalTrainUpdates; }
    public long getTotalSpawns() { return totalSpawns; }
}
