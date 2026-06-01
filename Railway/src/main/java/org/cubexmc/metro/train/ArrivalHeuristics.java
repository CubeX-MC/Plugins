package org.cubexmc.metro.train;

final class ArrivalHeuristics {

    private static final double THRESHOLD_BLOCKS = 2.0;

    private ArrivalHeuristics() {
    }

    static boolean shouldArrive(double distanceSquared, double speedBlocksPerTick) {
        double threshold = thresholdBlocks(speedBlocksPerTick);
        return distanceSquared <= threshold * threshold;
    }

    static double thresholdBlocks(double speedBlocksPerTick) {
        return THRESHOLD_BLOCKS;
    }
}
