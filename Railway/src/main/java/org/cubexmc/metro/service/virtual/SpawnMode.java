package org.cubexmc.metro.service.virtual;

/**
 * Spawn mode determines where a train is materialized when a player triggers
 * demand.
 */
public enum SpawnMode {
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
    CURRENT_STOP;

    public static SpawnMode from(String s, SpawnMode def) {
        if (s == null || s.isEmpty())
            return def;
        switch (s.trim().toLowerCase().replace("-", "_").replace(" ", "_")) {
            case "mid_segment":
            case "midsegment":
                return MID_SEGMENT;
            case "previous_stop":
            case "previousstop":
            case "previous":
                return PREVIOUS_STOP;
            case "platform_boundary":
            case "platformboundary":
            case "boundary":
                return PLATFORM_BOUNDARY;
            case "current_stop":
            case "currentstop":
            case "current":
                return CURRENT_STOP;
            default:
                return def;
        }
    }
}
