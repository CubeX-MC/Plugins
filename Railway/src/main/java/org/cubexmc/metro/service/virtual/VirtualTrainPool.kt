package org.cubexmc.metro.service.virtual

import java.util.PriorityQueue
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import org.cubexmc.metro.estimation.TravelTimeEstimator
import org.cubexmc.metro.model.Line
import org.cubexmc.metro.service.virtual.VirtualTrain.EventType
import org.cubexmc.metro.util.LineTopologyUtil

/**
 * Discrete-event pool of virtual trains for one line.
 */
class VirtualTrainPool(
    val lineId: String?,
    dwellTicks: Int,
) {
    private val virtualTrains: MutableMap<UUID, VirtualTrain> = HashMap()
    private val eventQueue: PriorityQueue<TrainEvent> =
        PriorityQueue(Comparator.comparingLong { event: TrainEvent -> event.tick })
    private val materializedIds: MutableSet<UUID> = HashSet()
    private val dwellTicks: Int = max(20, dwellTicks)
    private var cachedStopIds: List<String> = ArrayList()

    private class TrainEvent(
        val tick: Long,
        val trainId: UUID,
        val type: EventType,
    )

    private class SegmentBoundary(
        val fromStopIndex: Int,
        val toStopIndex: Int,
        val cumulativeTime: Double,
    )

    fun initialize(line: Line, headwaySeconds: Int, estimator: TravelTimeEstimator, currentTick: Long) {
        virtualTrains.clear()
        eventQueue.clear()
        materializedIds.clear()

        val stopIds = orderedStopIdsOrNull(line)
        if (stopIds == null || stopIds.size < 2) {
            return
        }
        cachedStopIds = ArrayList(stopIds)

        var totalTravelSeconds = 0.0
        for (index in 0 until stopIds.size - 1) {
            totalTravelSeconds += estimator.estimateSeconds(lineId, stopIds[index], stopIds[index + 1])
        }
        val dwellSecondsPerStop = dwellTicks / 20.0
        var totalCycleSeconds = totalTravelSeconds + stopIds.size * dwellSecondsPerStop
        if (totalCycleSeconds <= 0) {
            totalCycleSeconds = stopIds.size * 30.0
        }
        if (totalCycleSeconds.isNaN() || totalCycleSeconds.isInfinite()) {
            totalCycleSeconds = stopIds.size * 30.0
        }

        var trainCount = max(1, ceil(totalCycleSeconds / max(10, headwaySeconds)).toInt())
        trainCount = min(trainCount, 10)

        val boundaries = buildSegmentBoundaries(stopIds, estimator)
        val totalTime = if (boundaries.isEmpty()) {
            totalCycleSeconds
        } else {
            boundaries[boundaries.size - 1].cumulativeTime
        }

        for (index in 0 until trainCount) {
            val timeOffset = totalTime / trainCount * index
            var stopIndex = 0
            var targetIndex = min(1, stopIds.size - 1)
            var progress = 0.0
            var waiting = true
            var elapsedDwellSeconds = 0.0
            var segmentDurationSeconds = estimator.estimateSeconds(lineId, stopIds[0], stopIds[targetIndex])

            for (boundaryIndex in boundaries.indices) {
                val segment = boundaries[boundaryIndex]
                if (timeOffset <= segment.cumulativeTime) {
                    stopIndex = segment.fromStopIndex
                    targetIndex = segment.toStopIndex
                    val segmentStart = if (boundaryIndex > 0) {
                        boundaries[boundaryIndex - 1].cumulativeTime
                    } else {
                        0.0
                    }
                    val segmentDuration = segment.cumulativeTime - segmentStart
                    segmentDurationSeconds = max(0.05, segmentDuration - dwellSecondsPerStop)
                    if (segmentDuration > 0) {
                        val withinSegment = timeOffset - segmentStart
                        if (withinSegment <= dwellSecondsPerStop) {
                            waiting = true
                            elapsedDwellSeconds = withinSegment
                            progress = 0.0
                        } else {
                            waiting = false
                            if (segmentDurationSeconds > 0) {
                                progress = min(
                                    1.0,
                                    (withinSegment - dwellSecondsPerStop) / segmentDurationSeconds,
                                )
                            }
                        }
                    }
                    break
                }
                stopIndex = segment.toStopIndex
                targetIndex = min(stopIndex + 1, stopIds.size - 1)
            }

            val train = VirtualTrain(lineId, stopIds, dwellTicks, stopIndex, progress, currentTick)
            if (waiting) {
                train.initializeWaitingAt(stopIndex, elapsedDwellSeconds, currentTick)
            } else {
                train.initializeMovingBetween(stopIndex, targetIndex, progress, segmentDurationSeconds, currentTick)
            }
            virtualTrains[train.id] = train
            scheduleNextEvent(train, estimator, currentTick)
        }
    }

    /** Line is Kotlin now, but this preserves the old Java null guard for mocks. */
    private fun orderedStopIdsOrNull(line: Line): List<String>? = line.orderedStopIds

    private fun buildSegmentBoundaries(
        stopIds: List<String>,
        estimator: TravelTimeEstimator,
    ): List<SegmentBoundary> {
        val boundaries = ArrayList<SegmentBoundary>()
        var cumulative = 0.0
        val dwellSeconds = dwellTicks / 20.0
        for (index in 0 until stopIds.size - 1) {
            cumulative += dwellSeconds
            cumulative += estimator.estimateSeconds(lineId, stopIds[index], stopIds[index + 1])
            boundaries.add(SegmentBoundary(index, index + 1, cumulative))
        }
        return boundaries
    }

    fun tick(currentTick: Long, estimator: TravelTimeEstimator) {
        while (eventQueue.isNotEmpty() && eventQueue.peek().tick <= currentTick) {
            val event = eventQueue.poll()
            val train = virtualTrains[event.trainId] ?: continue
            if (materializedIds.contains(train.id)) {
                continue
            }

            val currentStop = train.currentStopIndex
            if (event.type == EventType.ARRIVAL) {
                train.onEvent(EventType.ARRIVAL, currentStop, event.tick, 0.0, cachedStopIds)
                eventQueue.add(TrainEvent(train.nextEventTick, train.id, EventType.DEPARTURE))
                continue
            }

            val stopIds = cachedStopIds
            var nextIndex = currentStop + 1
            if (nextIndex >= stopIds.size) {
                if (serviceIsLoop(stopIds)) {
                    nextIndex = 1
                    nextIndex = 0
                } else {
                    nextIndex = 0
                }
            }

            val fromId = stopIds[currentStop]
            if (nextIndex >= stopIds.size) {
                nextIndex = 0
            }
            val toId = stopIds[nextIndex]
            var duration = estimator.estimateSeconds(lineId, fromId, toId)
            if (duration <= 0) {
                duration = 10.0
            }

            train.onEvent(EventType.DEPARTURE, currentStop, event.tick, duration, stopIds)
            train.targetStopIndex = nextIndex
            eventQueue.add(TrainEvent(train.nextEventTick, train.id, EventType.ARRIVAL))
        }
    }

    private fun serviceIsLoop(stopIds: List<String>): Boolean = LineTopologyUtil.isLoop(stopIds)

    private fun scheduleNextEvent(
        train: VirtualTrain,
        estimator: TravelTimeEstimator,
        currentTick: Long,
    ) {
        if (train.lastEventType == EventType.ARRIVAL) {
            val nextTick = train.lastEventTick + dwellTicks
            train.nextEventTick = nextTick
            eventQueue.add(TrainEvent(nextTick, train.id, EventType.DEPARTURE))
        } else {
            eventQueue.add(TrainEvent(train.nextEventTick, train.id, EventType.ARRIVAL))
        }
    }

    fun findBestCandidateForStop(
        stopId: String,
        stopIds: List<String>,
        maxEtaSeconds: Double,
        estimator: TravelTimeEstimator,
        currentTick: Long,
    ): VirtualTrain? {
        val stopIndex = stopIds.indexOf(stopId)
        if (stopIndex < 0) {
            return null
        }

        var best: VirtualTrain? = null
        var bestEta = Double.POSITIVE_INFINITY
        for (train in virtualTrains.values) {
            if (materializedIds.contains(train.id) || train.isAtTerminal(stopIds)) {
                continue
            }
            val eta = train.estimateEtaToStop(stopIndex, estimator, currentTick)
            if (eta.isFinite() && eta <= maxEtaSeconds && eta < bestEta) {
                bestEta = eta
                best = train
            }
        }
        return best
    }

    fun markMaterialized(virtualTrainId: UUID) {
        materializedIds.add(virtualTrainId)
    }

    fun isMaterialized(virtualTrainId: UUID): Boolean = materializedIds.contains(virtualTrainId)

    fun clearMaterialized(virtualTrainId: UUID) {
        materializedIds.remove(virtualTrainId)
    }

    fun releaseMaterialized(virtualTrainId: UUID) {
        if (!materializedIds.remove(virtualTrainId)) {
            return
        }
        val train = virtualTrains[virtualTrainId] ?: return
        val nextType = if (train.lastEventType == EventType.ARRIVAL) {
            EventType.DEPARTURE
        } else {
            EventType.ARRIVAL
        }
        eventQueue.add(TrainEvent(train.nextEventTick, train.id, nextType))
    }

    fun returnToVirtual(
        virtualTrainId: UUID,
        stopIndex: Int,
        targetIndex: Int,
        progress: Double,
        isWaiting: Boolean,
        currentTick: Long,
        stopIds: List<String>,
    ) {
        materializedIds.remove(virtualTrainId)
        for (train in virtualTrains.values) {
            if (train.id == virtualTrainId) {
                train.restoreState(stopIndex, targetIndex, progress, isWaiting, currentTick, stopIds)
                val nextType = if (isWaiting) EventType.DEPARTURE else EventType.ARRIVAL
                eventQueue.add(TrainEvent(train.nextEventTick, train.id, nextType))
                break
            }
        }
    }

    fun removeVirtualTrain(virtualTrainId: UUID) {
        materializedIds.remove(virtualTrainId)
        virtualTrains.remove(virtualTrainId)
    }

    fun getVirtualTrains(): List<VirtualTrain> = ArrayList(virtualTrains.values)

    fun getActiveCount(stopIds: List<String>): Int {
        var count = 0
        for (train in virtualTrains.values) {
            if (!train.isAtTerminal(stopIds)) {
                count++
            }
        }
        return count
    }

    fun hasAvailableTrains(stopIds: List<String>): Boolean {
        for (train in virtualTrains.values) {
            if (!materializedIds.contains(train.id) && !train.isAtTerminal(stopIds)) {
                return true
            }
        }
        return false
    }

    fun refreshTopology(newStopIds: List<String>, estimator: TravelTimeEstimator, currentTick: Long) {
        val oldStopIds = ArrayList(cachedStopIds)
        cachedStopIds = ArrayList(newStopIds)
        eventQueue.clear()
        for (train in virtualTrains.values) {
            train.syncToNewTopology(oldStopIds, newStopIds, estimator, currentTick)
            if (materializedIds.contains(train.id)) {
                continue
            }
            scheduleNextEvent(train, estimator, currentTick)
        }
    }

    override fun toString(): String = String.format(
        "VirtualTrainPool[line=%s, trains=%d, materialized=%d]",
        lineId,
        virtualTrains.size,
        materializedIds.size,
    )
}
