package org.cubexmc.metro.service

interface DispatchStrategy {
    fun tick(service: LineService, currentTick: Long)

    fun requestStop(service: LineService, stopId: String?, currentTick: Long) {
    }

    /**
     * Notify strategy of topology changes
     *
     * @param service     LineService context
     * @param newStopIds  New ordered list of stop tags
     * @param currentTick Current server tick
     */
    fun refreshTopology(service: LineService, newStopIds: List<String>, currentTick: Long) {
    }
}
