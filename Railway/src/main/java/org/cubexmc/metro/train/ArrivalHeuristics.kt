package org.cubexmc.metro.train

internal object ArrivalHeuristics {

    private const val THRESHOLD_BLOCKS = 2.0

    @JvmStatic
    fun shouldArrive(distanceSquared: Double, speedBlocksPerTick: Double): Boolean {
        val threshold = thresholdBlocks(speedBlocksPerTick)
        return distanceSquared <= threshold * threshold
    }

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun thresholdBlocks(speedBlocksPerTick: Double): Double = THRESHOLD_BLOCKS
}
