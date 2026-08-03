package org.cubexmc.metro.train

import kotlin.math.max
import kotlin.math.min

internal object TrainStateMath {
    fun interface SegmentSecondsLookup {
        fun estimateSeconds(fromStopId: String?, toStopId: String?): Double
    }

    @JvmStatic
    fun estimateEtaSecondsToStop(
        state: TrainInstance.TrainState,
        stopIds: List<String?>?,
        currentIndex: Int,
        targetIndex: Int,
        loop: Boolean,
        stopId: String?,
        segmentElapsedSeconds: Double,
        segmentSecondsLookup: SegmentSecondsLookup?,
    ): Double {
        if (stopId == null || segmentSecondsLookup == null || stopIds == null || stopIds.size < 2) {
            return Double.POSITIVE_INFINITY
        }

        if (state == TrainInstance.TrainState.WAITING) {
            if (currentIndex >= 0 && currentIndex < stopIds.size && stopId == stopIds[currentIndex]) {
                return 0.0
            }
            return sumTravelSecondsUntilStop(stopIds, currentIndex, loop, stopId, segmentSecondsLookup)
        }

        if (state == TrainInstance.TrainState.MOVING) {
            if (targetIndex < 0 || targetIndex >= stopIds.size || currentIndex < 0 || currentIndex >= stopIds.size) {
                return Double.POSITIVE_INFINITY
            }

            val fromStopId = stopIds[currentIndex]
            val targetStopId = stopIds[targetIndex]
            val segmentTotal = max(0.0, segmentSecondsLookup.estimateSeconds(fromStopId, targetStopId))
            val remaining = max(0.0, segmentTotal - max(0.0, segmentElapsedSeconds))
            if (stopId == targetStopId) {
                return remaining
            }

            val downstream = sumTravelSecondsUntilStop(stopIds, targetIndex, loop, stopId, segmentSecondsLookup)
            if (!downstream.isFinite()) {
                return Double.POSITIVE_INFINITY
            }
            return remaining + downstream
        }

        return Double.POSITIVE_INFINITY
    }

    @JvmStatic
    fun estimateVirtualProgress(
        state: TrainInstance.TrainState,
        currentIndex: Int,
        targetIndex: Int,
        stopIds: List<String?>?,
        segmentElapsedSeconds: Double,
        segmentSecondsLookup: SegmentSecondsLookup?,
    ): Double {
        if (
            state != TrainInstance.TrainState.MOVING ||
            targetIndex < 0 ||
            stopIds == null ||
            currentIndex < 0 ||
            currentIndex >= stopIds.size ||
            targetIndex >= stopIds.size ||
            segmentSecondsLookup == null
        ) {
            return 0.0
        }

        val fromStopId = stopIds[currentIndex]
        val targetStopId = stopIds[targetIndex]
        val segmentTotal = max(0.0, segmentSecondsLookup.estimateSeconds(fromStopId, targetStopId))
        if (segmentTotal <= 0.0) {
            return 0.0
        }
        return min(1.0, max(0.0, segmentElapsedSeconds) / segmentTotal)
    }

    @JvmStatic
    fun isVirtualWaitingState(state: TrainInstance.TrainState): Boolean =
        state == TrainInstance.TrainState.WAITING || state == TrainInstance.TrainState.TERMINATING

    private fun sumTravelSecondsUntilStop(
        stopIds: List<String?>,
        fromIndex: Int,
        loop: Boolean,
        destinationStopId: String,
        segmentSecondsLookup: SegmentSecondsLookup,
    ): Double {
        if (fromIndex < 0 || fromIndex >= stopIds.size) {
            return Double.POSITIVE_INFINITY
        }

        val size = stopIds.size
        var sum = 0.0
        var index = fromIndex
        val maxSteps = if (loop) max(1, size) else max(1, size - 1 - fromIndex)

        repeat(maxSteps) {
            val next = nextArrivalIndex(index, size, loop, stopIds)
            if (next < 0) {
                return Double.POSITIVE_INFINITY
            }
            val fromStopId = stopIds[index]
            val nextStopId = stopIds[next]
            sum += max(0.0, segmentSecondsLookup.estimateSeconds(fromStopId, nextStopId))
            if (destinationStopId == nextStopId) {
                return sum
            }
            index = next
        }

        return Double.POSITIVE_INFINITY
    }

    private fun nextArrivalIndex(currentIndex: Int, size: Int, loop: Boolean, stopIds: List<String?>): Int {
        if (currentIndex < 0 || currentIndex >= size) {
            return -1
        }

        val next = currentIndex + 1
        if (next < size) {
            return next
        }

        if (!loop) {
            return -1
        }

        val repeatedTerminal = size > 1 && stopIds[0] != null && stopIds[0] == stopIds[size - 1]
        if (repeatedTerminal) {
            return if (size > 2) 1 else 0
        }
        return 0
    }
}
