package org.cubexmc.metro.service.virtual

import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import org.bukkit.Location
import org.bukkit.util.Vector
import org.cubexmc.metro.estimation.TravelTimeEstimator
import org.cubexmc.metro.manager.StopManager
import org.cubexmc.metro.model.Stop
import org.cubexmc.metro.util.LineTopologyUtil
import org.cubexmc.metro.util.SchedulerUtil

/**
 * In-memory train position used while no physical minecart is active.
 */
class VirtualTrain(
    val lineId: String?,
    stopIds: List<String>,
    dwellTicks: Int,
    initialStopIndex: Int,
    initialProgress: Double,
    initialTick: Long,
) {
    enum class State {
        WAITING,
        MOVING,
    }

    enum class EventType {
        ARRIVAL,
        DEPARTURE,
    }

    val id: UUID = UUID.randomUUID()
    private var stopIds: List<String> = ArrayList(stopIds)
    private val dwellTicks: Int = max(20, dwellTicks)
    private var isLoop: Boolean = LineTopologyUtil.isLoop(stopIds)

    var lastEventTick: Long = initialTick
        private set
    var lastEventType: EventType
        private set
    var currentStopIndex: Int = max(0, min(initialStopIndex, stopIds.size - 1))
        private set
    var targetStopIndex: Int
    var nextEventTick: Long = initialTick + 1
    private var currentPathDurationSeconds: Double = 10.0

    init {
        if (initialProgress > 0 && currentStopIndex < stopIds.size - 1) {
            lastEventType = EventType.DEPARTURE
            targetStopIndex = currentStopIndex + 1
        } else {
            lastEventType = EventType.ARRIVAL
            targetStopIndex = -1
        }
    }

    val state: State
        get() = if (lastEventType == EventType.ARRIVAL) State.WAITING else State.MOVING

    fun getSegmentProgress(currentTick: Long): Double {
        val elapsed = currentTick - lastEventTick
        if (lastEventType == EventType.ARRIVAL) {
            return min(1.0, elapsed.toDouble() / dwellTicks)
        }
        if (currentPathDurationSeconds <= 0) {
            return 0.0
        }
        val elapsedSeconds = elapsed / 20.0
        return min(1.0, elapsedSeconds / currentPathDurationSeconds)
    }

    fun getCurrentStopId(stopIds: List<String>): String? {
        if (currentStopIndex >= 0 && currentStopIndex < stopIds.size) {
            return stopIds[currentStopIndex]
        }
        return null
    }

    fun onEvent(
        type: EventType,
        stopIndex: Int,
        eventTick: Long,
        nextDurationSeconds: Double,
        stopIds: List<String>,
    ) {
        lastEventType = type
        currentStopIndex = stopIndex
        lastEventTick = eventTick
        currentPathDurationSeconds = nextDurationSeconds

        if (type == EventType.ARRIVAL) {
            targetStopIndex = -1
            nextEventTick = eventTick + dwellTicks
            return
        }

        var next = stopIndex + 1
        if (next >= stopIds.size && LineTopologyUtil.isLoop(stopIds)) {
            next = 1
        }
        targetStopIndex = next
        nextEventTick = eventTick + (nextDurationSeconds * 20.0).toLong()
    }

    fun initializeWaitingAt(stopIndex: Int, elapsedDwellSeconds: Double, currentTick: Long) {
        currentStopIndex = max(0, min(stopIndex, stopIds.size - 1))
        targetStopIndex = -1
        lastEventType = EventType.ARRIVAL
        val elapsedTicks = max(0L, min(dwellTicks.toLong(), Math.round(elapsedDwellSeconds * 20.0)))
        lastEventTick = currentTick - elapsedTicks
        currentPathDurationSeconds = dwellTicks / 20.0
        nextEventTick = max(currentTick + 1L, lastEventTick + dwellTicks)
    }

    fun initializeMovingBetween(
        fromIndex: Int,
        toIndex: Int,
        progress: Double,
        durationSeconds: Double,
        currentTick: Long,
    ) {
        currentStopIndex = max(0, min(fromIndex, stopIds.size - 1))
        targetStopIndex = max(0, min(toIndex, stopIds.size - 1))
        lastEventType = EventType.DEPARTURE
        currentPathDurationSeconds = max(0.05, durationSeconds)
        val safeProgress = max(0.0, min(1.0, progress))
        val elapsedTicks = max(0L, Math.round(currentPathDurationSeconds * 20.0 * safeProgress))
        lastEventTick = currentTick - elapsedTicks
        nextEventTick = max(
            currentTick + 1L,
            lastEventTick + max(1L, Math.round(currentPathDurationSeconds * 20.0)),
        )
    }

    fun estimateEtaToStop(stopIndex: Int, estimator: TravelTimeEstimator, currentTick: Long): Double {
        if (stopIndex < 0 || stopIndex >= stopIds.size) {
            return Double.POSITIVE_INFINITY
        }
        if (lastEventType == EventType.ARRIVAL && currentStopIndex == stopIndex) {
            return 0.0
        }
        if (!isLoop && stopIndex < currentStopIndex) {
            return Double.POSITIVE_INFINITY
        }

        var eta = 0.0
        if (lastEventType == EventType.DEPARTURE) {
            var remaining = 0.0
            if (targetStopIndex >= 0) {
                val fromId = stopIds[currentStopIndex]
                val toId = stopIds[targetStopIndex]
                val segmentTotal = estimator.estimateSeconds(lineId, fromId, toId)
                val elapsedSeconds = (currentTick - lastEventTick) / 20.0
                remaining = max(0.0, segmentTotal - elapsedSeconds)
            }
            eta += remaining
            if (targetStopIndex == stopIndex) {
                return eta
            }
            eta += sumTravelTime(targetStopIndex, stopIndex, estimator)
        } else {
            val elapsed = currentTick - lastEventTick
            eta += max(0.0, (dwellTicks - elapsed) / 20.0)
            eta += sumTravelTime(currentStopIndex, stopIndex, estimator)
        }
        return eta
    }

    private fun sumTravelTime(fromIndex: Int, toIndex: Int, estimator: TravelTimeEstimator): Double {
        var sum = 0.0
        var current = fromIndex
        var safety = 0
        while (current != toIndex && safety < stopIds.size * 2) {
            if (current >= stopIds.size - 1) {
                if (!isLoop) {
                    break
                }
                current = 0
            }

            val next = current + 1
            if (next >= stopIds.size) {
                break
            }
            sum += dwellTicks / 20.0
            sum += estimator.estimateSeconds(lineId, stopIds[current], stopIds[next])
            current = next
            safety++
        }
        return sum
    }

    fun estimateCurrentLocation(stopManager: StopManager, stopIds: List<String>): Location? {
        if (lastEventType == EventType.ARRIVAL) {
            if (currentStopIndex < stopIds.size) {
                return stopManager.getStop(stopIds[currentStopIndex])?.stopPointLocation
            }
            return null
        }

        val fromStop: Stop = stopManager.getStop(stopIds[currentStopIndex]) ?: return null
        val toStop: Stop = stopManager.getStop(stopIds[targetStopIndex]) ?: return null
        val from = fromStop.stopPointLocation ?: return null
        val to = toStop.stopPointLocation ?: return null
        val world = from.world ?: return null
        if (world != to.world) {
            return from
        }

        val progress = getSegmentProgress(SchedulerUtil.getCurrentTick())
        return Location(
            world,
            from.x + (to.x - from.x) * progress,
            from.y + (to.y - from.y) * progress,
            from.z + (to.z - from.z) * progress,
        )
    }

    fun estimateTravelDirection(stopManager: StopManager): Vector? {
        val fromIndex = currentStopIndex
        val toIndex = if (lastEventType == EventType.DEPARTURE && targetStopIndex >= 0) {
            targetStopIndex
        } else if (currentStopIndex + 1 < stopIds.size) {
            currentStopIndex + 1
        } else {
            currentStopIndex
        }
        if (fromIndex == toIndex) {
            return null
        }

        val fromStop = stopManager.getStop(stopIds[fromIndex]) ?: return null
        val toStop = stopManager.getStop(stopIds[toIndex]) ?: return null
        val from = fromStop.stopPointLocation ?: return null
        val to = toStop.stopPointLocation ?: return null
        val direction = to.toVector().subtract(from.toVector())
        direction.y = 0.0
        if (direction.lengthSquared() < 1e-6) {
            return null
        }
        return direction.normalize()
    }

    fun isNearStop(stopIndex: Int, maxEtaSeconds: Double, estimator: TravelTimeEstimator): Boolean {
        val eta = estimateEtaToStop(stopIndex, estimator, SchedulerUtil.getCurrentTick())
        return eta.isFinite() && eta <= maxEtaSeconds
    }

    fun restoreState(
        stopIndex: Int,
        targetIndex: Int,
        progress: Double,
        isWaiting: Boolean,
        currentTick: Long,
        stopIds: List<String>,
    ) {
        currentStopIndex = max(0, min(stopIndex, stopIds.size - 1))
        lastEventTick = currentTick
        currentPathDurationSeconds = 10.0
        if (isWaiting || targetIndex < 0) {
            lastEventType = EventType.ARRIVAL
            targetStopIndex = -1
            nextEventTick = currentTick + dwellTicks
        } else {
            lastEventType = EventType.DEPARTURE
            targetStopIndex = max(0, min(targetIndex, stopIds.size - 1))
            nextEventTick = currentTick + (10.0 * 20.0).toLong()
        }
    }

    fun isAtTerminal(stopIds: List<String>): Boolean {
        if (LineTopologyUtil.isLoop(stopIds)) {
            return false
        }
        return lastEventType == EventType.ARRIVAL && currentStopIndex >= stopIds.size - 1
    }

    fun syncToNewTopology(
        oldStopIds: List<String>,
        newStopIds: List<String>,
        estimator: TravelTimeEstimator,
        currentTick: Long,
    ) {
        val currentStopId = oldStopIds.getOrNull(currentStopIndex)
        val targetStopId = oldStopIds.getOrNull(targetStopIndex)
        var newCurrentIndex = currentStopId?.let { newStopIds.indexOf(it) } ?: -1

        if (newCurrentIndex == -1) {
            newCurrentIndex = max(0, min(currentStopIndex, newStopIds.size - 1))
            onEvent(EventType.ARRIVAL, newCurrentIndex, currentTick, 0.0, newStopIds)
            return
        }

        stopIds = ArrayList(newStopIds)
        isLoop = LineTopologyUtil.isLoop(stopIds)
        if (lastEventType == EventType.ARRIVAL) {
            currentStopIndex = newCurrentIndex
            targetStopIndex = -1
            return
        }

        var newTargetIndex = targetStopId?.let { newStopIds.indexOf(it) } ?: -1
        currentStopIndex = newCurrentIndex
        if (newTargetIndex == -1) {
            newTargetIndex = newCurrentIndex + 1
            if (newTargetIndex >= newStopIds.size) {
                newTargetIndex = if (LineTopologyUtil.isLoop(newStopIds)) 1 else 0
            }
            val fromId = newStopIds[newCurrentIndex]
            val toId = newStopIds[newTargetIndex]
            val newDuration = estimator.estimateSeconds(lineId, fromId, toId)
            val elapsedSeconds = (currentTick - lastEventTick) / 20.0
            val remaining = max(0.5, newDuration - elapsedSeconds)
            onEvent(EventType.DEPARTURE, newCurrentIndex, lastEventTick, newDuration, newStopIds)
            nextEventTick = currentTick + (remaining * 20.0).toLong()
            targetStopIndex = newTargetIndex
            return
        }

        if (newTargetIndex > newCurrentIndex + 1) {
            val interimTarget = newCurrentIndex + 1
            val interimId = newStopIds[interimTarget]
            val fromId = newStopIds[newCurrentIndex]
            val durationToInterim = estimator.estimateSeconds(lineId, fromId, interimId)
            val elapsedSeconds = (currentTick - lastEventTick) / 20.0
            if (elapsedSeconds >= durationToInterim) {
                onEvent(
                    EventType.DEPARTURE,
                    interimTarget,
                    currentTick,
                    estimator.estimateSeconds(lineId, interimId, newStopIds[interimTarget + 1]),
                    newStopIds,
                )
            } else {
                onEvent(EventType.DEPARTURE, newCurrentIndex, lastEventTick, durationToInterim, newStopIds)
                nextEventTick = lastEventTick + (durationToInterim * 20.0).toLong()
                targetStopIndex = interimTarget
            }
        } else {
            targetStopIndex = newTargetIndex
        }
    }

    override fun toString(): String = String.format(
        "VirtualTrain[%s, line=%s, event=%s, stop=%d->%d]",
        id.toString().substring(0, 8),
        lineId,
        lastEventType,
        currentStopIndex,
        targetStopIndex,
    )
}
