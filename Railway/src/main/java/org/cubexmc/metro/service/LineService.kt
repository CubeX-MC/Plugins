package org.cubexmc.metro.service

import org.bukkit.NamespacedKey
import org.bukkit.util.Vector
import org.cubexmc.metro.Metro
import org.cubexmc.metro.manager.StopManager
import org.cubexmc.metro.model.EntityModelController
import org.cubexmc.metro.model.Line
import org.cubexmc.metro.model.Stop
import org.cubexmc.metro.service.strategy.GlobalDispatchStrategy
import org.cubexmc.metro.service.strategy.LocalDispatchStrategy
import org.cubexmc.metro.train.TrainInstance
import org.cubexmc.metro.util.LineTopologyUtil
import org.cubexmc.metro.util.LocationUtil
import org.cubexmc.metro.util.SchedulerUtil
import java.util.LinkedHashSet
import java.util.UUID
import java.util.function.ToDoubleBiFunction
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

class LineService(
    val plugin: Metro,
    private val manager: LineServiceManager,
    val lineId: String,
    headwaySeconds: Int,
    dwellTicks: Int,
    trainCars: Int,
    private val dispatchStrategy: DispatchStrategy?,
) {
    private val spawner = TrainSpawner(plugin, this)
    private val activeTrainStorage = ArrayList<TrainInstance>()
    private val pendingRemoval: MutableSet<TrainInstance> = LinkedHashSet()

    var headwaySeconds = headwaySeconds
    var dwellTicks = dwellTicks
    var trainCars = max(1, trainCars)
        set(value) {
            field = max(1, value)
        }
    private var lastDepartureTick = -1L
    var totalTicks = 0L
        private set
    var totalTrainUpdates = 0L
        private set
    var totalSpawns = 0L
        private set

    val headwayTicks: Int
        get() = max(20, headwaySeconds * 20)
    val trainKey: NamespacedKey
        get() = manager.getTrainKey()
    val blockSectionManager: BlockSectionManager
        get() = manager.getBlockSectionManager()
    val stopManager: StopManager
        get() = plugin.stopManager
    val line: Line?
        get() = plugin.lineManager.getLine(lineId)
    val activeTrains: List<TrainInstance>
        get() = ArrayList(activeTrainStorage)
    val cartSpeed: Double
        get() {
            val currentLine = line
            if (currentLine != null) {
                val lineSpeed = currentLine.getMaxSpeed() ?: throw NullPointerException("line max speed")
                if (lineSpeed > 0) {
                    return lineSpeed
                }
            }
            return plugin.cartSpeed
        }
    val trainSpacing: Double
        get() {
            val currentLine = line
            var entityType: String? = if (currentLine != null) {
                lineEntityTypeOrNull(currentLine)
            } else {
                EntityModelController.MINECART_ENTITY_TYPE
            }
            val modelController = plugin.entityModelController
            if (currentLine != null && !currentLine.hasEntityTypeOverride() &&
                plugin.config.getBoolean("entity-model.enabled", false) && modelController != null
            ) {
                entityType = modelController.getDefaultEntityTypeRaw()
            }
            return if (modelController != null) {
                modelController.getRecommendedSpacing(entityType, plugin.trainSpacing)
            } else {
                EntityModelController.recommendedSpacing(entityType, plugin.trainSpacing)
            }
        }
    val isGlobalMode: Boolean
        get() = dispatchStrategy is GlobalDispatchStrategy
    val isLoopLine: Boolean
        get() {
            val currentLine = line ?: return false
            return LineTopologyUtil.isLoop(currentLine.orderedStopIds)
        }

    fun tick() {
        totalTicks++
        val currentTick = SchedulerUtil.getCurrentTick()
        dispatchStrategy?.tick(this, currentTick)
        updateActiveTrains(currentTick)
    }

    fun shutdown() {
        for (train in activeTrainStorage) {
            queueTrainRemoval(train)
        }
        flushPendingTrainRemoval()
    }

    fun refreshStops() {
        val line = line ?: return
        val newStops = line.orderedStopIds
        val currentTick = SchedulerUtil.getCurrentTick()
        dispatchStrategy?.refreshTopology(this, newStops, currentTick)
        if (activeTrainStorage.isEmpty()) {
            return
        }

        val localDispatch = dispatchStrategy as? LocalDispatchStrategy
        var retiredTrains = 0
        var releasedVirtualTrains = 0
        for (train in ArrayList(activeTrainStorage)) {
            val virtualTrainId = train.virtualTrainId
            if (localDispatch != null && virtualTrainId != null) {
                localDispatch.releaseMaterializedTrain(virtualTrainId)
                releasedVirtualTrains++
            }
            train.finishImmediately()
            queueTrainRemoval(train)
            retiredTrains++
        }
        flushPendingTrainRemoval()

        plugin.logger.info(
            "Refreshed topology for line $lineId; retired $retiredTrains active train(s)" +
                if (releasedVirtualTrains > 0) {
                    " and released $releasedVirtualTrains virtual train(s) to prevent stop-index desync."
                } else {
                    " to prevent stop-index desync."
                },
        )
    }

    fun addTrain(train: TrainInstance, currentTick: Long) {
        activeTrainStorage.add(train)
        totalSpawns++
        manager.registerTrain(train)
    }

    fun spawnTrain(currentTick: Long) {
        spawner.spawnTrainAtFirstStop(currentTick)
    }

    fun spawnTrainForVirtual(
        currentTick: Long,
        fromStopIndex: Int,
        toStopIndex: Int,
        progress: Double,
        virtualTrainId: UUID,
        targetStopId: String,
    ): TrainInstance? = spawner.spawnTrainForVirtual(
        currentTick,
        fromStopIndex,
        toStopIndex,
        progress,
        virtualTrainId,
        targetStopId,
    )

    fun requestStop(stopId: String, currentTick: Long) {
        dispatchStrategy?.requestStop(this, stopId, currentTick)
    }

    private fun updateActiveTrains(currentTick: Long) {
        for (train in activeTrainStorage) {
            totalTrainUpdates++
            train.update(currentTick)
            if (train.isFinished) {
                queueTrainRemoval(train)
            }
        }
        flushPendingTrainRemoval()
    }

    fun handleTrainDerail(train: TrainInstance?) {
        if (train == null) {
            return
        }
        train.finishImmediately()
        queueTrainRemoval(train)
    }

    fun virtualizeBackToPool(train: TrainInstance, currentTick: Long) {
        (dispatchStrategy as? LocalDispatchStrategy)?.returnTrainToPool(train, currentTick)
    }

    private fun queueTrainRemoval(train: TrainInstance?) {
        if (train != null) {
            pendingRemoval.add(train)
        }
    }

    private fun flushPendingTrainRemoval() {
        if (pendingRemoval.isEmpty()) {
            return
        }
        val currentTick = SchedulerUtil.getCurrentTick()
        for (train in pendingRemoval) {
            restoreVirtualAuthorityIfNeeded(train, currentTick)
            manager.unregisterTrain(train)
            train.cleanup()
            activeTrainStorage.remove(train)
        }
        pendingRemoval.clear()
    }

    private fun restoreVirtualAuthorityIfNeeded(train: TrainInstance?, currentTick: Long) {
        val localDispatch = dispatchStrategy as? LocalDispatchStrategy ?: return
        val virtualTrainId = train?.virtualTrainId ?: return
        if (!localDispatch.isMaterializedTrain(virtualTrainId)) {
            return
        }
        localDispatch.returnTrainToPool(train, currentTick)
    }

    fun estimateNextEtaSeconds(currentTick: Long): Int {
        val headwayTicks = headwayTicks
        if (lastDepartureTick < 0) {
            val mod = currentTick % headwayTicks
            val etaTicks = if (mod == 0L) headwayTicks.toLong() else headwayTicks - mod
            return ceil(etaTicks / 20.0).toInt()
        }
        var elapsed = currentTick - lastDepartureTick
        if (elapsed < 0) {
            elapsed = 0
        }
        var remaining = headwayTicks - elapsed % headwayTicks
        if (remaining == headwayTicks.toLong()) {
            remaining = 0
        }
        return ceil(remaining / 20.0).toInt()
    }

    fun estimateNextEtaSeconds(currentTick: Long, stopId: String?): Int {
        val virtualEta = estimateVirtualEtaSeconds(stopId)
        if (virtualEta != null) {
            return virtualEta
        }
        val departureEtaSeconds = estimateNextEtaSeconds(currentTick).toDouble()
        val line = line
        if (line == null || stopId.isNullOrEmpty()) {
            return ceil(departureEtaSeconds).toInt()
        }
        val etaSeconds = ServiceEtaCalculator.estimateScheduledEtaSeconds(
            line.orderedStopIds,
            stopId,
            dwellTicks,
            departureEtaSeconds,
            ToDoubleBiFunction { fromStopId, toStopId ->
                plugin.travelTimeEstimator.estimateSeconds(lineId, fromStopId, toStopId)
            },
        )
        return ceil(etaSeconds).toInt()
    }

    private fun estimateVirtualEtaSeconds(stopId: String?): Int? {
        val localDispatch = dispatchStrategy as? LocalDispatchStrategy ?: return null
        if (stopId.isNullOrEmpty()) {
            return null
        }
        val line = line ?: return null
        val pool = localDispatch.pool ?: return null
        val stopIds = line.orderedStopIds
        val stopIndex = stopIds.indexOf(stopId)
        if (stopIndex < 0) {
            return null
        }
        val candidate = pool.findBestCandidateForStop(
            stopId,
            stopIds,
            Double.POSITIVE_INFINITY,
            plugin.travelTimeEstimator,
            SchedulerUtil.getCurrentTick(),
        ) ?: return null
        val etaSeconds = candidate.estimateEtaToStop(
            stopIndex,
            plugin.travelTimeEstimator,
            SchedulerUtil.getCurrentTick(),
        )
        if (!etaSeconds.isFinite()) {
            return null
        }
        return max(0, etaSeconds.roundToInt())
    }

    fun isDepartureWindow(currentTick: Long): Boolean =
        lastDepartureTick < 0 || currentTick - lastDepartureTick >= headwayTicks

    fun markDeparture(currentTick: Long) {
        lastDepartureTick = currentTick
    }

    fun tryMarkDeparture(currentTick: Long): Boolean {
        if (!isDepartureWindow(currentTick)) {
            return false
        }
        markDeparture(currentTick)
        return true
    }

    fun markVirtualizedTrain(currentTick: Long) {
        if (!isGlobalMode) {
            lastDepartureTick = max(0L, currentTick - headwayTicks)
        }
    }

    fun computeTravelDirection(fromStop: Stop?, toStop: Stop?): Vector {
        if (fromStop == null) {
            return Vector(0, 0, 1)
        }
        val direction = vectorFromYawOrNull(fromStop.launchYaw)
        return if (direction != null && direction.lengthSquared() > 0) {
            direction.normalize()
        } else {
            Vector(0, 0, 1)
        }
    }

    fun buildSectionKey(fromStopId: String, toStopId: String): String =
        "$lineId:$fromStopId->$toStopId"

    private fun vectorFromYawOrNull(yaw: Float): Vector? = LocationUtil.vectorFromYaw(yaw)

    private fun lineEntityTypeOrNull(line: Line): String? = line.getEntityType()

    fun refreshPhysicsEngines() {
        for (train in activeTrainStorage) {
            train.refreshPhysicsEngine()
        }
    }

    fun refreshEntityModels() {
        for (train in activeTrainStorage) {
            train.refreshEntityModels()
        }
    }

}
