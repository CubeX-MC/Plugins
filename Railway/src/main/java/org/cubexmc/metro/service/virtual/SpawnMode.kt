package org.cubexmc.metro.service.virtual

import java.util.Locale

/**
 * Spawn mode determines where a train is materialized when a player triggers
 * demand.
 */
enum class SpawnMode {
    /**
     * Spawn at an interpolated position between two stops, on the nearest rail.
     * Best experience but may fail if no rail is found.
     */
    MID_SEGMENT,

    /**
     * Spawn at the previous stop and let the train travel to the player's stop.
     * Most reliable but player waits longer.
     */
    PREVIOUS_STOP,

    /**
     * Spawn just outside the platform boundary of the target stop.
     * Train appears to "arrive" from outside the station.
     */
    PLATFORM_BOUNDARY,

    /**
     * Spawn directly at the target stop's stop point.
     * Instant arrival but less realistic.
     */
    CURRENT_STOP,
    ;

    companion object {
        @JvmStatic
        fun from(s: String?, def: SpawnMode?): SpawnMode? {
            if (s.isNullOrEmpty()) {
                return def
            }
            return when (s.trim().lowercase(Locale.getDefault()).replace("-", "_").replace(" ", "_")) {
                "mid_segment", "midsegment" -> MID_SEGMENT
                "previous_stop", "previousstop", "previous" -> PREVIOUS_STOP
                "platform_boundary", "platformboundary", "boundary" -> PLATFORM_BOUNDARY
                "current_stop", "currentstop", "current" -> CURRENT_STOP
                else -> def
            }
        }
    }
}
