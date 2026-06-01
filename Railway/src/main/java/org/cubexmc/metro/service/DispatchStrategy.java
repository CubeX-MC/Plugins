package org.cubexmc.metro.service;

import java.util.List;

public interface DispatchStrategy {
    void tick(LineService service, long currentTick);

    default void requestStop(LineService service, String stopId, long currentTick) {
    }

    /**
     * Notify strategy of topology changes
     * 
     * @param service     LineService context
     * @param newStopIds  New ordered list of stop tags
     * @param currentTick Current server tick
     */
    default void refreshTopology(LineService service, List<String> newStopIds, long currentTick) {
    }
}
