package org.cubexmc.metro.service

import org.bukkit.NamespacedKey
import org.cubexmc.metro.Metro
import org.cubexmc.metro.model.Line
import org.cubexmc.metro.service.strategy.GlobalDispatchStrategy
import org.cubexmc.metro.service.strategy.LocalDispatchStrategy
import org.cubexmc.metro.train.TrainInstance
import org.cubexmc.metro.util.SchedulerUtil
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class LineServiceManager(
    private val plugin: Metro,
) {
    private val lineIdToService: MutableMap<String, LineService> = ConcurrentHashMap()
    private val trainsById: MutableMap<UUID, TrainInstance> = ConcurrentHashMap()
    private val trainsByMinecart: MutableMap<UUID, TrainInstance> = ConcurrentHashMap()
    private val trainKey = NamespacedKey(plugin, "train-id")
    private val blockSectionManager = BlockSectionManager()
    private var heartbeat: Any? = null
    private val operationMode: OperationMode? = OperationMode.from(plugin.serviceModeRaw, OperationMode.LOCAL)
    private var lastMetricsLogTick = -1L

    init {
        for (line in plugin.lineManager.allLines) {
            if (line.isServiceEnabled) {
                createAndRegisterService(line.id, line.headwaySeconds, line.dwellTicks, line.trainCars)
            }
        }
        startHeartbeat()
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        val period = maxOf(1L, plugin.serviceHeartbeatIntervalTicks.toLong())
        heartbeat = SchedulerUtil.globalRun(
            plugin,
            Runnable {
                val currentTick = SchedulerUtil.getCurrentTick()
                val startNanos = System.nanoTime()
                for (service in lineIdToService.values) {
                    service.tick()
                }
                plugin.lineManager.tick()
                plugin.stopManager.tick()
                maybeLogServiceMetrics(currentTick, System.nanoTime() - startNanos)
            },
            period,
            period,
        )
    }

    private fun stopHeartbeat() {
        val currentHeartbeat = heartbeat ?: return
        SchedulerUtil.cancelTask(currentHeartbeat)
        heartbeat = null
    }

    fun shutdown() {
        stopHeartbeat()
        for (service in lineIdToService.values) {
            service.shutdown()
        }
        lineIdToService.clear()
        trainsById.clear()
        trainsByMinecart.clear()
        plugin.lineManager.saveLines()
        plugin.stopManager.saveStops()
    }

    fun getService(lineId: String): LineService? = lineIdToService[lineId]

    fun registerService(lineId: String, service: LineService) {
        lineIdToService[lineId] = service
    }

    fun createAndRegisterService(
        lineId: String,
        headwaySeconds: Int,
        dwellTicks: Int,
        trainCars: Int,
    ): LineService {
        val service = LineService(
            plugin,
            this,
            lineId,
            headwaySeconds,
            dwellTicks,
            trainCars,
            createStrategy(),
        )
        registerService(lineId, service)
        return service
    }

    private fun createStrategy(): DispatchStrategy =
        if (operationMode == OperationMode.GLOBAL) GlobalDispatchStrategy() else LocalDispatchStrategy()

    fun estimateNextEtaSeconds(lineId: String, stopId: String): Int {
        val service = lineIdToService[lineId] ?: return plugin.serviceDefaultHeadwaySeconds
        return service.estimateNextEtaSeconds(SchedulerUtil.getCurrentTick(), stopId)
    }

    fun startService(line: Line?) {
        if (line == null || lineIdToService.containsKey(line.id)) {
            return
        }
        createAndRegisterService(line.id, line.headwaySeconds, line.dwellTicks, line.trainCars)
    }

    fun requestStop(lineId: String?, stopId: String?) {
        if (lineId == null || stopId == null) {
            return
        }
        var service = lineIdToService[lineId]
        if (service == null) {
            val line = plugin.lineManager.getLine(lineId)
            if (line != null && line.isServiceEnabled) {
                startService(line)
                service = lineIdToService[lineId]
            }
        }
        service?.requestStop(stopId, SchedulerUtil.getCurrentTick())
    }

    fun stopService(lineId: String) {
        lineIdToService.remove(lineId)?.shutdown()
    }

    fun rebuildFromLines() {
        stopHeartbeat()
        for (service in lineIdToService.values) {
            service.shutdown()
        }
        lineIdToService.clear()
        trainsById.clear()
        trainsByMinecart.clear()

        for (line in plugin.lineManager.allLines) {
            if (line.isServiceEnabled) {
                createAndRegisterService(line.id, line.headwaySeconds, line.dwellTicks, line.trainCars)
            }
        }
        startHeartbeat()
    }

    fun getBlockSectionManager(): BlockSectionManager = blockSectionManager

    fun getTrainKey(): NamespacedKey = trainKey

    fun registerTrain(train: TrainInstance) {
        trainsById[train.id] = train
        train.consist.getCars().forEach { car -> trainsByMinecart[car.uniqueId] = train }
    }

    fun unregisterTrain(train: TrainInstance) {
        trainsById.remove(train.id)
        train.consist.getCars().forEach { car -> trainsByMinecart.remove(car.uniqueId) }
    }

    fun getTrainByMinecart(minecartId: UUID): TrainInstance? = trainsByMinecart[minecartId]

    private fun maybeLogServiceMetrics(currentTick: Long, tickElapsedNanos: Long) {
        val metricsInterval = plugin.serviceMetricsLogIntervalTicks
        if (metricsInterval <= 0 ||
            lastMetricsLogTick >= 0 && currentTick - lastMetricsLogTick < metricsInterval
        ) {
            return
        }
        lastMetricsLogTick = currentTick
        var totalActiveTrains = 0L
        var totalSpawns = 0L
        var totalUpdates = 0L
        for (service in lineIdToService.values) {
            totalActiveTrains += service.activeTrains.size
            totalSpawns += service.totalSpawns
            totalUpdates += service.totalTrainUpdates
        }
        plugin.logger.info(
            "[ServiceMetrics] services=${lineIdToService.size}" +
                ", activeTrains=$totalActiveTrains" +
                ", totalSpawns=$totalSpawns" +
                ", totalTrainUpdates=$totalUpdates" +
                ", heartbeatNanos=$tickElapsedNanos",
        )
    }
}
