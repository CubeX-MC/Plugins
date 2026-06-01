package org.cubexmc.metro.service.strategy;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.cubexmc.metro.Metro;
import org.cubexmc.metro.model.Line;
import org.cubexmc.metro.model.Stop;
import org.cubexmc.metro.service.DispatchStrategy;
import org.cubexmc.metro.util.SchedulerUtil;
import org.cubexmc.metro.service.LineService;
import org.cubexmc.metro.service.virtual.VirtualTrain;
import org.cubexmc.metro.service.virtual.VirtualTrainPool;
import org.cubexmc.metro.train.TrainInstance;

/**
 * Local mode dispatch: maintain a virtual Metro network where trains run
 * continuously in a virtual layer. When a virtual train arrives at a station
 * with player demand, it is materialized into a physical train.
 * 
 * Key design: Virtual trains simulate real-time positions. Physical trains
 * only spawn when a virtual train ACTUALLY arrives at a station where
 * players are waiting.
 */
public class LocalDispatchStrategy implements DispatchStrategy {

    private VirtualTrainPool pool;
    private boolean initialized = false;

    // Track which stop has player demand (for materialization check)
    private String currentDemandStopId = null;
    private final Map<String, Long> stopDemandExpiryTicks = new HashMap<>();

    // Cooldown to prevent rapid re-spawn
    private long lastSpawnTick = -1L;
    private static final long SPAWN_COOLDOWN_TICKS = 60; // 3 seconds

    @Override
    public void tick(LineService service, long currentTick) {
        // Initialize pool on first tick
        if (!initialized) {
            initializePool(service, currentTick);
            initialized = true;
        }

        if (pool == null) {
            return;
        }

        Metro plugin = service.getPlugin();
        Line line = service.getLine();
        if (line == null)
            return;
        List<String> stops = line.getOrderedStopIds();

        // First, clean up stale materialized markers
        cleanupStaleMaterializations(service);

        // Update player demand
        currentDemandStopId = resolveDemandStop(service, currentTick, stops);

        // Check if we already have a physical train for this line
        boolean hasPhysicalTrain = !service.getActiveTrains().isEmpty();

        // Check spawn cooldown
        boolean onCooldown = lastSpawnTick > 0 && (currentTick - lastSpawnTick) < SPAWN_COOLDOWN_TICKS;

        // Update virtual train positions (this moves them forward in time)
        // IMPORTANT: Update pool BEFORE checking for materialization
        // This ensures that if a train was just cleaned up (and dwell expired),
        // it departs before we try to spawn it again.
        pool.tick(currentTick, plugin.getTravelTimeEstimator());

        // Try to materialize a virtual train that has ARRIVED at the demand stop
        if (currentDemandStopId != null && !hasPhysicalTrain && !onCooldown) {
            tryMaterializeArrivedTrain(service, currentTick, stops);
        }
    }

    @Override
    public void requestStop(LineService service, String stopId, long currentTick) {
        Line line = service.getLine();
        if (line == null || stopId == null) {
            return;
        }
        List<String> stops = line.getOrderedStopIds();
        int stopIndex = stops.indexOf(stopId);
        if (stopIndex < 0 || isTerminalDemand(service, stopIndex, stops)) {
            return;
        }
        long keepAliveTicks = Math.max(20L * 600L, service.getHeadwayTicks() * 2L);
        stopDemandExpiryTicks.put(stopId, currentTick + keepAliveTicks);
        currentDemandStopId = stopId;
    }

    private String resolveDemandStop(LineService service, long currentTick, List<String> stops) {
        cleanupExpiredDemands(currentTick);

        Stop occupiedStop = findPlayerOccupiedStop(service);
        if (occupiedStop != null) {
            requestStop(service, occupiedStop.getId(), currentTick);
        }

        if (stopDemandExpiryTicks.isEmpty()) {
            return null;
        }

        String bestStopId = null;
        double bestEta = Double.POSITIVE_INFINITY;
        Metro plugin = service.getPlugin();
        for (String stopId : stopDemandExpiryTicks.keySet()) {
            int stopIndex = stops.indexOf(stopId);
            if (stopIndex < 0 || isTerminalDemand(service, stopIndex, stops)) {
                continue;
            }
            VirtualTrain candidate = findClosestVirtualTrain(stopIndex, plugin, currentTick);
            double eta = candidate == null
                    ? Double.POSITIVE_INFINITY
                    : candidate.estimateEtaToStop(stopIndex, plugin.getTravelTimeEstimator(), currentTick);
            if (eta < bestEta) {
                bestEta = eta;
                bestStopId = stopId;
            }
        }
        return bestStopId;
    }

    private void cleanupExpiredDemands(long currentTick) {
        Iterator<Map.Entry<String, Long>> iterator = stopDemandExpiryTicks.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() <= currentTick) {
                iterator.remove();
            }
        }
    }

    /**
     * Try to materialize a virtual train that has arrived at the demand stop.
     * Only spawn if a virtual train is at (or very close to) the demand stop.
     */
    private void tryMaterializeArrivedTrain(LineService service, long currentTick, List<String> stops) {
        Metro plugin = service.getPlugin();
        int demandIndex = stops.indexOf(currentDemandStopId);
        if (demandIndex < 0)
            return;
        if (isTerminalDemand(service, demandIndex, stops)) {
            stopDemandExpiryTicks.remove(currentDemandStopId);
            currentDemandStopId = null;
            return;
        }

        // Look for a virtual train that has arrived at the demand stop
        for (VirtualTrain vt : pool.getVirtualTrains()) {
            if (pool.isMaterialized(vt.getId())) {
                continue;
            }

            // Check if this virtual train is at the demand stop
            // It must be in WAITING state at the exact stop index
            if (vt.getState() == VirtualTrain.State.WAITING && vt.getCurrentStopIndex() == demandIndex) {
                // Virtual train has arrived! Materialize it.

                if (materializeTrain(service, vt, currentDemandStopId, demandIndex, stops, currentTick)) {
                    stopDemandExpiryTicks.remove(currentDemandStopId);
                    lastSpawnTick = currentTick;
                    return; // Only spawn one train
                }
                lastSpawnTick = currentTick;
                return; // Cool down failed attempts too, otherwise the server logs every tick.
            }
        }

        // No train has arrived yet - this is normal, player waits for ETA
        // Log occasionally to show system is working
        if (currentTick % 100 == 0) { // Every 5 seconds
            VirtualTrain closest = findClosestVirtualTrain(demandIndex, plugin, currentTick);
            if (closest != null) {
                double eta = closest.estimateEtaToStop(demandIndex, plugin.getTravelTimeEstimator(),
                        SchedulerUtil.getCurrentTick());
                plugin.getLogger().fine("[LocalDispatch] Waiting for train to arrive at " +
                        currentDemandStopId + ", ETA=" + String.format("%.1f", eta) + "s");
            }
        }
    }

    private VirtualTrain findClosestVirtualTrain(int demandIndex, Metro plugin, long currentTick) {
        VirtualTrain closest = null;
        double bestEta = Double.POSITIVE_INFINITY;

        for (VirtualTrain vt : pool.getVirtualTrains()) {
            if (pool.isMaterialized(vt.getId()))
                continue;
            double eta = vt.estimateEtaToStop(demandIndex, plugin.getTravelTimeEstimator(),
                    currentTick);
            if (Double.isFinite(eta) && eta < bestEta) {
                bestEta = eta;
                closest = vt;
            }
        }
        return closest;
    }

    /**
     * Clean up materialized markers for virtual trains whose physical trains no
     * longer exist.
     */
    private void cleanupStaleMaterializations(LineService service) {
        if (pool == null)
            return;

        List<TrainInstance> activeTrains = service.getActiveTrains();
        List<VirtualTrain> virtualTrains = pool.getVirtualTrains();

        for (VirtualTrain vt : virtualTrains) {
            if (!pool.isMaterialized(vt.getId())) {
                continue;
            }

            // Check if any active train has this virtual train ID
            boolean found = false;
            for (TrainInstance ti : activeTrains) {
                if (vt.getId().equals(ti.getVirtualTrainId())) {
                    found = true;
                    break;
                }
            }

            // If no physical train found, un-materialize
            if (!found) {
                // Just clear the materialized flag - virtual train continues from its current
                // position
                pool.clearMaterialized(vt.getId());

            }
        }
    }

    private void initializePool(LineService service, long currentTick) {
        Line line = service.getLine();
        if (line == null) {
            return;
        }

        pool = new VirtualTrainPool(service.getLineId(), service.getDwellTicks());
        pool.initialize(line, service.getHeadwaySeconds(),
                service.getPlugin().getTravelTimeEstimator(), currentTick);

    }

    private Stop findPlayerOccupiedStop(LineService service) {
        Line line = service.getLine();
        if (line == null)
            return null;

        List<String> stopIds = line.getOrderedStopIds();
        Metro plugin = service.getPlugin();
        double radius = plugin.getLocalActivationRadius();
        double radiusSq = radius * radius;

        for (String sid : stopIds) {
            Stop s = plugin.getStopManager().getStop(sid);
            if (s == null || s.getStopPointLocation() == null)
                continue;
            if (plugin.isPlayerWithinStopRadius(s, radiusSq)) {
                return s;
            }
        }
        return null;
    }

    private boolean isTerminalDemand(LineService service, int stopIndex, List<String> stops) {
        return stopIndex >= stops.size() - 1 && !service.isLoopLine();
    }

    private boolean materializeTrain(LineService service, VirtualTrain vt,
            String targetStopId, int targetIndex, List<String> stops, long currentTick) {

        Metro plugin = service.getPlugin();

        // Don't spawn at terminal for non-loop lines
        if (targetIndex >= stops.size() - 1 && !service.isLoopLine()) {
            plugin.getLogger().fine("[LocalDispatch] Skipping terminal stop " + targetStopId);
            return false;
        }

        // Virtual train is at targetStop, spawn the physical train there
        int fromStopIndex = vt.getCurrentStopIndex();
        int toStopIndex = fromStopIndex; // Target the station we are at!

        // Spawn the physical train at the current stop
        TrainInstance train = service.spawnTrainForVirtual(
                currentTick, fromStopIndex, toStopIndex, 0.0,
                vt.getId(), targetStopId);

        if (train != null) {
            pool.markMaterialized(vt.getId());
            // plugin.getLogger().info("[LocalDispatch] Train materialized at " +
            // targetStopId);
            return true;
        } else {
            plugin.getLogger().warning("[LocalDispatch] Failed to spawn train at " + targetStopId);
            return false;
        }
    }

    /**
     * Return a physical train back to the virtual pool.
     */
    public void returnTrainToPool(TrainInstance train, long currentTick) {
        if (pool == null)
            return;

        java.util.UUID vtId = train.getVirtualTrainId();
        if (vtId == null)
            return;

        Line line = train.getService().getLine();
        if (line == null)
            return;
        List<String> stopIds = line.getOrderedStopIds();

        TrainInstance.VirtualizationState state = train.getVirtualizationState(currentTick);
        pool.returnToVirtual(vtId, state.currentIndex, state.targetIndex,
                state.progress, state.isWaiting, currentTick, stopIds);
    }

    public VirtualTrainPool getPool() {
        return pool;
    }

    public void releaseMaterializedTrain(java.util.UUID virtualTrainId) {
        if (pool == null || virtualTrainId == null) {
            return;
        }
        pool.releaseMaterialized(virtualTrainId);
    }

    public boolean isMaterializedTrain(java.util.UUID virtualTrainId) {
        return pool != null && virtualTrainId != null && pool.isMaterialized(virtualTrainId);
    }

    @Override
    public void refreshTopology(LineService service, List<String> newStopIds, long currentTick) {
        if (pool == null)
            return;

        Metro plugin = service.getPlugin();
        pool.refreshTopology(newStopIds, plugin.getTravelTimeEstimator(), currentTick);

    }
}
