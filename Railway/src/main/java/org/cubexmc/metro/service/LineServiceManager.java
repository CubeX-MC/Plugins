package org.cubexmc.metro.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.cubexmc.metro.Metro;
import org.cubexmc.metro.manager.LineManager;
import org.cubexmc.metro.model.Line;
import org.cubexmc.metro.service.strategy.GlobalDispatchStrategy;
import org.cubexmc.metro.service.strategy.LocalDispatchStrategy;
import org.cubexmc.metro.train.TrainInstance;
import org.cubexmc.metro.util.SchedulerUtil;

public class LineServiceManager {

    private final Metro plugin;
    private final Map<String, LineService> lineIdToService = new ConcurrentHashMap<>();
    private final Map<UUID, TrainInstance> trainsById = new ConcurrentHashMap<>();
    private final Map<UUID, TrainInstance> trainsByMinecart = new ConcurrentHashMap<>();
    private final NamespacedKey trainKey;
    private final BlockSectionManager blockSectionManager = new BlockSectionManager();
    private Object heartbeat;
    private final OperationMode operationMode;
    private long lastMetricsLogTick = -1L;

    public LineServiceManager(Metro plugin) {
        this.plugin = plugin;
        this.operationMode = OperationMode.from(plugin.getServiceModeRaw(),
                OperationMode.LOCAL);
        this.trainKey = new NamespacedKey(plugin, "train-id");

        LineManager lineManager = plugin.getLineManager();
        for (Line line : lineManager.getAllLines()) {
            if (line.isServiceEnabled()) {
                createAndRegisterService(line.getId(), line.getHeadwaySeconds(), line.getDwellTicks(),
                        line.getTrainCars());
            }
        }

        startHeartbeat();
    }

    private void startHeartbeat() {
        stopHeartbeat();
        long period = Math.max(1L, plugin.getServiceHeartbeatIntervalTicks());
        this.heartbeat = SchedulerUtil.globalRun(plugin, () -> {
            long currentTick = SchedulerUtil.getCurrentTick();
            long startNanos = System.nanoTime();
            for (LineService service : lineIdToService.values()) {
                service.tick();
            }
            plugin.getLineManager().tick();
            plugin.getStopManager().tick();
            maybeLogServiceMetrics(currentTick, System.nanoTime() - startNanos);
        }, period, period);
    }

    private void stopHeartbeat() {
        if (heartbeat != null) {
            SchedulerUtil.cancelTask(heartbeat);
            heartbeat = null;
        }
    }

    public void shutdown() {
        stopHeartbeat();
        for (LineService service : lineIdToService.values()) {
            service.shutdown();
        }
        lineIdToService.clear();
        trainsById.clear();
        trainsByMinecart.clear();
        plugin.getLineManager().saveLines();
        plugin.getStopManager().saveStops();
    }

    public LineService getService(String lineId) {
        return lineIdToService.get(lineId);
    }

    public void registerService(String lineId, LineService service) {
        lineIdToService.put(lineId, service);
    }

    public LineService createAndRegisterService(String lineId, int headwaySeconds, int dwellTicks, int trainCars) {
        DispatchStrategy strategy = createStrategy();
        LineService service = new LineService(plugin, this, lineId, headwaySeconds, dwellTicks, trainCars, strategy);
        registerService(lineId, service);
        return service;
    }

    private DispatchStrategy createStrategy() {
        switch (operationMode) {
            case GLOBAL:
                return new GlobalDispatchStrategy();
            case LOCAL:
            default:
                return new LocalDispatchStrategy();
        }
    }

    public int estimateNextEtaSeconds(String lineId, String stopId) {
        LineService service = lineIdToService.get(lineId);
        if (service == null)
            return plugin.getServiceDefaultHeadwaySeconds();
        long currentTick = SchedulerUtil.getCurrentTick();
        return service.estimateNextEtaSeconds(currentTick, stopId);
    }

    public void startService(Line line) {
        if (line == null)
            return;
        if (lineIdToService.containsKey(line.getId()))
            return;
        createAndRegisterService(line.getId(), line.getHeadwaySeconds(), line.getDwellTicks(), line.getTrainCars());
    }

    public void requestStop(String lineId, String stopId) {
        if (lineId == null || stopId == null) {
            return;
        }
        LineService service = lineIdToService.get(lineId);
        if (service == null) {
            Line line = plugin.getLineManager().getLine(lineId);
            if (line != null && line.isServiceEnabled()) {
                startService(line);
                service = lineIdToService.get(lineId);
            }
        }
        if (service != null) {
            service.requestStop(stopId, SchedulerUtil.getCurrentTick());
        }
    }

    public void stopService(String lineId) {
        LineService service = lineIdToService.remove(lineId);
        if (service != null) {
            service.shutdown();
        }
    }

    public void rebuildFromLines() {
        stopHeartbeat();
        for (LineService service : lineIdToService.values()) {
            service.shutdown();
        }
        lineIdToService.clear();
        trainsById.clear();
        trainsByMinecart.clear();

        LineManager lineManager = plugin.getLineManager();
        for (Line line : lineManager.getAllLines()) {
            if (line.isServiceEnabled()) {
                createAndRegisterService(line.getId(), line.getHeadwaySeconds(), line.getDwellTicks(),
                        line.getTrainCars());
            }
        }
        startHeartbeat();
    }

    public BlockSectionManager getBlockSectionManager() {
        return blockSectionManager;
    }

    public NamespacedKey getTrainKey() {
        return trainKey;
    }

    public void registerTrain(TrainInstance train) {
        trainsById.put(train.getId(), train);
        train.getConsist().getCars().forEach(car -> trainsByMinecart.put(car.getUniqueId(), train));
    }

    public void unregisterTrain(TrainInstance train) {
        trainsById.remove(train.getId());
        train.getConsist().getCars().forEach(car -> trainsByMinecart.remove(car.getUniqueId()));
    }

    public TrainInstance getTrainByMinecart(UUID minecartId) {
        return trainsByMinecart.get(minecartId);
    }

    private void maybeLogServiceMetrics(long currentTick, long tickElapsedNanos) {
        int metricsInterval = plugin.getServiceMetricsLogIntervalTicks();
        if (metricsInterval <= 0) {
            return;
        }
        if (lastMetricsLogTick >= 0 && currentTick - lastMetricsLogTick < metricsInterval) {
            return;
        }
        lastMetricsLogTick = currentTick;
        long totalActiveTrains = 0L;
        long totalSpawns = 0L;
        long totalUpdates = 0L;
        for (LineService service : lineIdToService.values()) {
            totalActiveTrains += service.getActiveTrains().size();
            totalSpawns += service.getTotalSpawns();
            totalUpdates += service.getTotalTrainUpdates();
        }
        plugin.getLogger().info("[ServiceMetrics] services=" + lineIdToService.size()
                + ", activeTrains=" + totalActiveTrains
                + ", totalSpawns=" + totalSpawns
                + ", totalTrainUpdates=" + totalUpdates
                + ", heartbeatNanos=" + tickElapsedNanos);
    }
}
