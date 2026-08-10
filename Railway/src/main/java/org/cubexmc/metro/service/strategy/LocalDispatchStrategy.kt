package org.cubexmc.metro.service.strategy

import org.cubexmc.metro.Metro
import org.cubexmc.metro.model.Stop
import org.cubexmc.metro.service.DispatchStrategy
import org.cubexmc.metro.service.LineService
import org.cubexmc.metro.service.virtual.VirtualTrain
import org.cubexmc.metro.service.virtual.VirtualTrainPool
import org.cubexmc.metro.train.TrainInstance
import org.cubexmc.metro.util.SchedulerUtil
import java.util.UUID
import kotlin.math.max

/** Local mode keeps trains virtual until one reaches a stop with player demand. */
class LocalDispatchStrategy : DispatchStrategy {
    var pool: VirtualTrainPool? = null
        private set

    private var initialized = false
    private var currentDemandStopId: String? = null
    private val stopDemandExpiryTicks: MutableMap<String, Long> = HashMap()
    private var lastSpawnTick = -1L

    override fun tick(service: LineService, currentTick: Long) {
        if (!initialized) {
            initializePool(service, currentTick)
            initialized = true
        }
        val currentPool = pool ?: return
        val plugin = service.plugin
        val line = service.line ?: return
        val stops = line.orderedStopIds

        cleanupStaleMaterializations(service)
        currentDemandStopId = resolveDemandStop(service, currentTick, stops)
        val hasPhysicalTrain = service.activeTrains.isNotEmpty()
        val onCooldown = lastSpawnTick > 0 && currentTick - lastSpawnTick < 60L

        currentPool.tick(currentTick, plugin.travelTimeEstimator)
        if (currentDemandStopId != null && !hasPhysicalTrain && !onCooldown) {
            tryMaterializeArrivedTrain(service, currentTick, stops)
        }
    }

    override fun requestStop(service: LineService, stopId: String?, currentTick: Long) {
        val line = service.line
        if (line == null || stopId == null) {
            return
        }
        val stops = line.orderedStopIds
        val stopIndex = stops.indexOf(stopId)
        if (stopIndex < 0 || isTerminalDemand(service, stopIndex, stops)) {
            return
        }
        val keepAliveTicks = max(20L * 600L, service.headwayTicks * 2L)
        stopDemandExpiryTicks[stopId] = currentTick + keepAliveTicks
        currentDemandStopId = stopId
    }

    private fun resolveDemandStop(
        service: LineService,
        currentTick: Long,
        stops: List<String>,
    ): String? {
        cleanupExpiredDemands(currentTick)
        findPlayerOccupiedStop(service)?.let { occupiedStop ->
            requestStop(service, occupiedStop.id, currentTick)
        }
        if (stopDemandExpiryTicks.isEmpty()) {
            return null
        }

        var bestStopId: String? = null
        var bestEta = Double.POSITIVE_INFINITY
        val plugin = service.plugin
        for (stopId in stopDemandExpiryTicks.keys) {
            val stopIndex = stops.indexOf(stopId)
            if (stopIndex < 0 || isTerminalDemand(service, stopIndex, stops)) {
                continue
            }
            val candidate = findClosestVirtualTrain(stopIndex, plugin, currentTick)
            val eta = candidate?.estimateEtaToStop(
                stopIndex,
                plugin.travelTimeEstimator,
                currentTick,
            ) ?: Double.POSITIVE_INFINITY
            if (eta < bestEta) {
                bestEta = eta
                bestStopId = stopId
            }
        }
        return bestStopId
    }

    private fun cleanupExpiredDemands(currentTick: Long) {
        val iterator = stopDemandExpiryTicks.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value <= currentTick) {
                iterator.remove()
            }
        }
    }

    private fun tryMaterializeArrivedTrain(
        service: LineService,
        currentTick: Long,
        stops: List<String>,
    ) {
        val demandStopId = currentDemandStopId ?: return
        val demandIndex = stops.indexOf(demandStopId)
        if (demandIndex < 0) {
            return
        }
        if (isTerminalDemand(service, demandIndex, stops)) {
            stopDemandExpiryTicks.remove(demandStopId)
            currentDemandStopId = null
            return
        }

        val currentPool = pool ?: return
        for (virtualTrain in currentPool.getVirtualTrains()) {
            if (currentPool.isMaterialized(virtualTrain.id)) {
                continue
            }
            if (virtualTrain.state == VirtualTrain.State.WAITING &&
                virtualTrain.currentStopIndex == demandIndex
            ) {
                if (materializeTrain(service, virtualTrain, demandStopId, demandIndex, stops, currentTick)) {
                    stopDemandExpiryTicks.remove(demandStopId)
                    lastSpawnTick = currentTick
                    return
                }
                lastSpawnTick = currentTick
                return
            }
        }

        if (currentTick % 100 == 0L) {
            val plugin = service.plugin
            val closest = findClosestVirtualTrain(demandIndex, plugin, currentTick)
            if (closest != null) {
                val eta = closest.estimateEtaToStop(
                    demandIndex,
                    plugin.travelTimeEstimator,
                    SchedulerUtil.getCurrentTick(),
                )
                plugin.logger.fine(
                    "[LocalDispatch] Waiting for train to arrive at $demandStopId, " +
                        "ETA=${String.format("%.1f", eta)}s",
                )
            }
        }
    }

    private fun findClosestVirtualTrain(
        demandIndex: Int,
        plugin: Metro,
        currentTick: Long,
    ): VirtualTrain? {
        val currentPool = pool ?: return null
        var closest: VirtualTrain? = null
        var bestEta = Double.POSITIVE_INFINITY
        for (virtualTrain in currentPool.getVirtualTrains()) {
            if (currentPool.isMaterialized(virtualTrain.id)) {
                continue
            }
            val eta = virtualTrain.estimateEtaToStop(
                demandIndex,
                plugin.travelTimeEstimator,
                currentTick,
            )
            if (eta.isFinite() && eta < bestEta) {
                bestEta = eta
                closest = virtualTrain
            }
        }
        return closest
    }

    private fun cleanupStaleMaterializations(service: LineService) {
        val currentPool = pool ?: return
        val activeTrains = service.activeTrains
        for (virtualTrain in currentPool.getVirtualTrains()) {
            if (!currentPool.isMaterialized(virtualTrain.id)) {
                continue
            }
            val found = activeTrains.any { train -> virtualTrain.id == train.virtualTrainId }
            if (!found) {
                currentPool.clearMaterialized(virtualTrain.id)
            }
        }
    }

    private fun initializePool(service: LineService, currentTick: Long) {
        val line = service.line ?: return
        pool = VirtualTrainPool(service.lineId, service.dwellTicks).also { newPool ->
            newPool.initialize(
                line,
                service.headwaySeconds,
                service.plugin.travelTimeEstimator,
                currentTick,
            )
        }
    }

    private fun findPlayerOccupiedStop(service: LineService): Stop? {
        val line = service.line ?: return null
        val plugin = service.plugin
        val radius = plugin.localActivationRadius
        val radiusSq = radius * radius
        for (stopId in line.orderedStopIds) {
            val stop = plugin.stopManager.getStop(stopId)
            if (stop?.stopPointLocation == null) {
                continue
            }
            if (plugin.isPlayerWithinStopRadius(stop, radiusSq)) {
                return stop
            }
        }
        return null
    }

    private fun isTerminalDemand(service: LineService, stopIndex: Int, stops: List<String>): Boolean =
        stopIndex >= stops.size - 1 && !service.isLoopLine

    private fun materializeTrain(
        service: LineService,
        virtualTrain: VirtualTrain,
        targetStopId: String,
        targetIndex: Int,
        stops: List<String>,
        currentTick: Long,
    ): Boolean {
        val plugin = service.plugin
        if (targetIndex >= stops.size - 1 && !service.isLoopLine) {
            plugin.logger.fine("[LocalDispatch] Skipping terminal stop $targetStopId")
            return false
        }
        val fromStopIndex = virtualTrain.currentStopIndex
        val train = service.spawnTrainForVirtual(
            currentTick,
            fromStopIndex,
            fromStopIndex,
            0.0,
            virtualTrain.id,
            targetStopId,
        )
        if (train != null) {
            pool?.markMaterialized(virtualTrain.id)
            return true
        }
        plugin.logger.warning("[LocalDispatch] Failed to spawn train at $targetStopId")
        return false
    }

    fun returnTrainToPool(train: TrainInstance, currentTick: Long) {
        val currentPool = pool ?: return
        val virtualTrainId = train.virtualTrainId ?: return
        val line = train.service.line ?: return
        val state = train.getVirtualizationState(currentTick)
        currentPool.returnToVirtual(
            virtualTrainId,
            state.currentIndex,
            state.targetIndex,
            state.progress,
            state.isWaiting,
            currentTick,
            line.orderedStopIds,
        )
    }

    fun releaseMaterializedTrain(virtualTrainId: UUID?) {
        if (virtualTrainId != null) {
            pool?.releaseMaterialized(virtualTrainId)
        }
    }

    fun isMaterializedTrain(virtualTrainId: UUID?): Boolean =
        virtualTrainId != null && pool?.isMaterialized(virtualTrainId) == true

    override fun refreshTopology(service: LineService, newStopIds: List<String>, currentTick: Long) {
        pool?.refreshTopology(newStopIds, service.plugin.travelTimeEstimator, currentTick)
    }
}
