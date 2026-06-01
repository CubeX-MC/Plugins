package org.cubexmc.metro.service.virtual;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import org.cubexmc.metro.service.virtual.VirtualTrain.EventType;
import org.cubexmc.metro.util.LineTopologyUtil;

import org.cubexmc.metro.estimation.TravelTimeEstimator;
import org.cubexmc.metro.model.Line;

/**
 * Manages a pool of virtual trains for a single line.
 * Virtual trains run continuously in the background and can be materialized
 * when players are nearby.
 */
public class VirtualTrainPool {

    private final String lineId;
    private final Map<UUID, VirtualTrain> virtualTrains = new HashMap<>(); // Replaced List with Map for faster lookups
    private final PriorityQueue<TrainEvent> eventQueue;
    private final Set<UUID> materializedIds = new HashSet<>();

    private final int dwellTicks;
    private List<String> cachedStopIds = new ArrayList<>(); // Store the topology currently used by trains

    // Internal event class for the PriorityQueue
    private static class TrainEvent {
        final long tick;
        final UUID trainId;
        final EventType type;

        TrainEvent(long tick, UUID trainId, EventType type) {
            this.tick = tick;
            this.trainId = trainId;
            this.type = type;
        }
    }

    /**
     * Create a new virtual train pool for a line.
     * 
     * @param lineId     The line ID
     * @param dwellTicks Dwell time at each stop in ticks
     */
    public VirtualTrainPool(String lineId, int dwellTicks) {
        this.lineId = lineId;
        this.dwellTicks = Math.max(20, dwellTicks);
        this.eventQueue = new PriorityQueue<>(Comparator.comparingLong(e -> e.tick));
    }

    /**
     * Initialize the pool with virtual trains based on headway.
     * Trains are distributed evenly along the line.
     * 
     * @param line           The line configuration
     * @param headwaySeconds Headway between trains in seconds
     * @param estimator      Travel time estimator
     * @param currentTick    Current server tick
     */
    public void initialize(Line line, int headwaySeconds, TravelTimeEstimator estimator, long currentTick) {
        virtualTrains.clear();
        eventQueue.clear();
        materializedIds.clear();

        List<String> stopIds = line.getOrderedStopIds();
        if (stopIds == null || stopIds.size() < 2) {
            return;
        }
        this.cachedStopIds = new ArrayList<>(stopIds);

        // Calculate total cycle time (travel + dwell at each stop)
        double totalTravelSeconds = 0;
        for (int i = 0; i < stopIds.size() - 1; i++) {
            totalTravelSeconds += estimator.estimateSeconds(lineId, stopIds.get(i), stopIds.get(i + 1));
        }
        double dwellSecondsPerStop = dwellTicks / 20.0;
        double totalCycleSeconds = totalTravelSeconds + (stopIds.size() * dwellSecondsPerStop);

        if (totalCycleSeconds <= 0) {
            totalCycleSeconds = stopIds.size() * 30.0; // Fallback
        }

        // Safety check for estimator to prevent NaN/Infinity issues
        if (Double.isNaN(totalCycleSeconds) || Double.isInfinite(totalCycleSeconds)) {
            totalCycleSeconds = stopIds.size() * 30.0;
        }

        // Determine number of virtual trains
        int trainCount = Math.max(1, (int) Math.ceil(totalCycleSeconds / Math.max(10, headwaySeconds)));

        // Cap at reasonable number
        trainCount = Math.min(trainCount, 10);

        // Calculate segment boundaries for distribution
        List<SegmentBoundary> boundaries = buildSegmentBoundaries(stopIds, estimator);
        double totalTime = boundaries.isEmpty() ? totalCycleSeconds
                : boundaries.get(boundaries.size() - 1).cumulativeTime;

        // Distribute trains evenly
        for (int i = 0; i < trainCount; i++) {
            double timeOffset = (totalTime / trainCount) * i;

            // Find segment and progress for this time offset
            int stopIndex = 0;
            int targetIndex = Math.min(1, stopIds.size() - 1);
            double progress = 0.0;
            boolean waiting = true;
            double elapsedDwellSeconds = 0.0;
            double segmentDurationSeconds = estimator.estimateSeconds(lineId, stopIds.get(0), stopIds.get(targetIndex));

            for (int j = 0; j < boundaries.size(); j++) {
                SegmentBoundary seg = boundaries.get(j);
                if (timeOffset <= seg.cumulativeTime) {
                    stopIndex = seg.fromStopIndex;
                    targetIndex = seg.toStopIndex;
                    double segmentStart = j > 0 ? boundaries.get(j - 1).cumulativeTime : 0;
                    double segmentDuration = seg.cumulativeTime - segmentStart;
                    segmentDurationSeconds = Math.max(0.05, segmentDuration - dwellSecondsPerStop);
                    if (segmentDuration > 0) {
                        double withinSegment = timeOffset - segmentStart;
                        if (withinSegment <= dwellSecondsPerStop) {
                            // Still in dwell time
                            waiting = true;
                            elapsedDwellSeconds = withinSegment;
                            progress = 0.0;
                        } else {
                            // In transit
                            waiting = false;
                            if (segmentDurationSeconds > 0) {
                                progress = Math.min(1.0, (withinSegment - dwellSecondsPerStop) / segmentDurationSeconds);
                            }
                        }
                    }
                    break;
                }
                stopIndex = seg.toStopIndex;
                targetIndex = Math.min(stopIndex + 1, stopIds.size() - 1);
            }

            VirtualTrain vt = new VirtualTrain(lineId, stopIds, dwellTicks, stopIndex, progress, currentTick);
            if (waiting) {
                vt.initializeWaitingAt(stopIndex, elapsedDwellSeconds, currentTick);
            } else {
                vt.initializeMovingBetween(stopIndex, targetIndex, progress, segmentDurationSeconds, currentTick);
            }
            virtualTrains.put(vt.getId(), vt);

            // Schedule first event
            scheduleNextEvent(vt, estimator, currentTick);
        }
    }

    private List<SegmentBoundary> buildSegmentBoundaries(List<String> stopIds, TravelTimeEstimator estimator) {
        List<SegmentBoundary> boundaries = new ArrayList<>();
        double cumulative = 0;
        double dwellSeconds = dwellTicks / 20.0;

        for (int i = 0; i < stopIds.size() - 1; i++) {
            cumulative += dwellSeconds; // Dwell at departure stop
            cumulative += estimator.estimateSeconds(lineId, stopIds.get(i), stopIds.get(i + 1));
            boundaries.add(new SegmentBoundary(i, i + 1, cumulative));
        }
        return boundaries;
    }

    private static class SegmentBoundary {
        final int fromStopIndex;
        final int toStopIndex;
        final double cumulativeTime;

        SegmentBoundary(int from, int to, double time) {
            this.fromStopIndex = from;
            this.toStopIndex = to;
            this.cumulativeTime = time;
        }
    }

    /**
     * Update all virtual trains.
     * 
     * @param currentTick Current server tick
     * @param estimator   Travel time estimator
     */
    /**
     * Update all virtual trains by processing events up to currentTick.
     * This is the core Discrete Event Simulation loop.
     * 
     * @param currentTick Current server tick
     * @param estimator   Travel time estimator
     */
    public void tick(long currentTick, TravelTimeEstimator estimator) {
        // Process all events that are due (scheduled time <= current time)
        while (!eventQueue.isEmpty() && eventQueue.peek().tick <= currentTick) {
            TrainEvent event = eventQueue.poll();

            VirtualTrain vt = virtualTrains.get(event.trainId);
            if (vt == null)
                continue; // Train removed?

            // If materialized, we still process the event conceptually to keep the virtual
            // state in sync?
            // Or do we pause?
            // The polling version said: "Skip materialized trains".
            // If we skip processing, the train effectively pauses in virtual space.
            // When it un-materializes, it calls returnToVirtual which resets state.
            // SO: If materialized, we should probably NOT update the virtual state
            // automatically
            // because the physical train is now the authority.
            // WE JUST DROP THE EVENT. The returnToVirtual will re-schedule events.

            if (materializedIds.contains(vt.getId())) {
                continue;
            }

            // Validate event order (sanity check)
            if (vt.getNextEventTick() != event.tick) {
                // Old/stale event? In a robust system we'd handle this.
                // For now, trust the queue but verifies against train's state
                // If the train was somehow updated otherwise, this event might be stale.
            }

            // Process the event
            // 1. Update Train State
            // We need to calculate the *result* of this event.
            // If Arrival -> State becomes Waiting, next event is Departure
            // If Departure -> State becomes Moving, next event is Arrival at next stop

            // CALCULATE DATA FOR NEXT STEP
            int currentStop = vt.getCurrentStopIndex();

            if (event.type == EventType.ARRIVAL) {
                // Train just arrived at 'currentStop'
                // It stays there for dwellTicks
                // Update train state to: WAITING at currentStop
                // NOTE: 'onEvent' takes the state *after* the event.
                // ARRIVAL -> sets type=ARRIVAL (Waiting)
                // ARRIVAL -> sets type=ARRIVAL (Waiting)
                // Use cached stops
                vt.onEvent(EventType.ARRIVAL, currentStop, event.tick, 0, cachedStopIds); // duration irrelevant for
                                                                                          // waiting

                // Schedule next: DEPARTURE
                eventQueue.add(new TrainEvent(vt.getNextEventTick(), vt.getId(), EventType.DEPARTURE));

            } else if (event.type == EventType.DEPARTURE) {
                // Train is departing from 'currentStop'
                // It will go to 'currentStop + 1'
                // Calculate duration
                List<String> stopIds = cachedStopIds;
                int nextIndex = currentStop + 1;

                // Handle Loop/Terminal logic
                if (nextIndex >= stopIds.size()) {
                    // Terminal reached
                    boolean isLoop = serviceIsLoop(stopIds);
                    if (isLoop) {
                        nextIndex = 1; // 0 is start/end duplicate usually?
                        // Actually let's assume standard loop: A->B->C->A
                        // If stops are [A, B, C, A], size is 4. indices 0,1,2,3.
                        // Arrive at 3 (A). Depart from 3?
                        // Usually loop lines just run continuously.
                        // Let's use 1 if indices match start/end?
                        // Or maybe index 0?
                        // If stopIds[0].equals(stopIds[last]), then arrival at last == arrival at
                        // first.
                        // The previous logic in VirtualTrain handle terminal:
                        // "currentStopIndex = 0; target = -1; state = WAITING"
                        // So it basically teleports to start and waits.

                        // Let's simulate that:
                        // Departure from Terminal -> Instant teleport to Start ARRIVAL?
                        // Or just set next index to 1?
                        // Let's rely on TravelTimeEstimator to give us time from Last -> First?
                        // If the line defines A->B->...->A, then Last->First is dist 0 ?
                        // The estimator likely returns 0 or small value.

                        // SIMPLIFICATION:
                        // If at terminal, we simply map back to 0 immediately.
                        nextIndex = 0;
                        // And we treat this as a "reset" event.
                        // But wait, the standard logic was: "wait at start".
                        // So Departure from Terminal -> Arrival at Start (Duration 0/Small)
                    } else {
                        // Non-loop. Turnaround.
                        // "Departure" from terminal -> Arrival at start (Teleport)
                        nextIndex = 0;
                    }
                }

                // Calculate travel time
                String fromId = stopIds.get(currentStop);
                // Be careful if nextIndex was wrapped
                if (nextIndex >= stopIds.size())
                    nextIndex = 0; // Safety for non-loop
                String toId = stopIds.get(nextIndex);

                double duration = estimator.estimateSeconds(lineId, fromId, toId);
                if (duration <= 0)
                    duration = 10.0;

                // Update train state
                // Update train state
                vt.onEvent(EventType.DEPARTURE, currentStop, event.tick, duration, stopIds);
                // Accessing package-private setTargetStopIndex if needed, but onEvent sets it.
                // However, internal logic in onEvent sets target = stop + 1.
                // We might need to correct it if we wrapped around.
                vt.setTargetStopIndex(nextIndex);

                // Schedule next: ARRIVAL
                eventQueue.add(new TrainEvent(vt.getNextEventTick(), vt.getId(), EventType.ARRIVAL));
            }
        }
    }

    private boolean serviceIsLoop(List<String> stopIds) {
        return LineTopologyUtil.isLoop(stopIds);
    }

    private void scheduleNextEvent(VirtualTrain vt, TravelTimeEstimator estimator, long currentTick) {
        // Should calculate based on current state where the next event is
        // Used during initialization or restoration

        // If last was ARRIVAL -> Next is DEPARTURE
        if (vt.getLastEventType() == EventType.ARRIVAL) {
            long nextTick = vt.getLastEventTick() + dwellTicks;
            // Ensure it's in future? If past, schedule for now?
            // For initialization it might be in past if we used currentTick as base.
            // But we want to distribute them.

            // If we initialized a train "mid-dwell", nextTick might be soon.
            vt.setNextEventTick(nextTick);
            eventQueue.add(new TrainEvent(nextTick, vt.getId(), EventType.DEPARTURE));
        } else {
            // If last was DEPARTURE -> Next is ARRIVAL
            // need duration
            // We can't easily get duration here without the logic from tick().
            // But valid state implies currentPathDurationSeconds is set.
            // So we just use getNextEventTick() which was set in constructor/restore
            eventQueue.add(new TrainEvent(vt.getNextEventTick(), vt.getId(), EventType.ARRIVAL));
        }
    }

    /**
     * Find the best virtual train candidate to materialize for a given stop.
     * Returns the train with the smallest ETA that is not already materialized.
     * 
     * @param stopId        Target stop ID
     * @param stopIds       Ordered stop IDs for the line
     * @param maxEtaSeconds Maximum ETA in seconds
     * @param estimator     Travel time estimator
     * @return Best candidate, or null if none found
     */
    public VirtualTrain findBestCandidateForStop(String stopId, List<String> stopIds,
            double maxEtaSeconds, TravelTimeEstimator estimator, long currentTick) {
        int stopIndex = stopIds.indexOf(stopId);
        if (stopIndex < 0)
            return null;

        VirtualTrain best = null;
        double bestEta = Double.POSITIVE_INFINITY;

        for (VirtualTrain vt : virtualTrains.values()) {
            if (materializedIds.contains(vt.getId())) {
                continue;
            }
            if (vt.isAtTerminal(stopIds)) {
                continue;
            }

            double eta = vt.estimateEtaToStop(stopIndex, estimator, currentTick);
            if (Double.isFinite(eta) && eta <= maxEtaSeconds && eta < bestEta) {
                bestEta = eta;
                best = vt;
            }
        }

        return best;
    }

    /**
     * Mark a virtual train as materialized (physical instance created).
     */
    public void markMaterialized(UUID virtualTrainId) {
        materializedIds.add(virtualTrainId);
    }

    /**
     * Check if a virtual train is currently materialized.
     */
    public boolean isMaterialized(UUID virtualTrainId) {
        return materializedIds.contains(virtualTrainId);
    }

    /**
     * Clear materialized flag without restoring state.
     * Used when the physical train was removed but the virtual train
     * should continue from its current simulated position.
     */
    public void clearMaterialized(UUID virtualTrainId) {
        materializedIds.remove(virtualTrainId);
    }

    /**
     * Release a materialized train back to virtual control using its current
     * synchronized virtual state.
     */
    public void releaseMaterialized(UUID virtualTrainId) {
        if (!materializedIds.remove(virtualTrainId)) {
            return;
        }

        VirtualTrain vt = virtualTrains.get(virtualTrainId);
        if (vt == null) {
            return;
        }

        if (vt.getLastEventType() == EventType.ARRIVAL) {
            eventQueue.add(new TrainEvent(vt.getNextEventTick(), vt.getId(), EventType.DEPARTURE));
        } else {
            eventQueue.add(new TrainEvent(vt.getNextEventTick(), vt.getId(), EventType.ARRIVAL));
        }
    }

    /**
     * Return a physical train back to virtual state.
     * 
     * @param virtualTrainId The virtual train ID
     * @param stopIndex      Current stop index
     * @param targetIndex    Target stop index (-1 if waiting)
     * @param progress       Segment progress (0-1)
     * @param isWaiting      Whether the train was waiting
     * @param currentTick    Current tick
     */

    public void returnToVirtual(UUID virtualTrainId, int stopIndex, int targetIndex,
            double progress, boolean isWaiting, long currentTick, List<String> stopIds) {
        materializedIds.remove(virtualTrainId);

        for (VirtualTrain vt : virtualTrains.values()) {
            if (vt.getId().equals(virtualTrainId)) {
                vt.restoreState(stopIndex, targetIndex, progress, isWaiting, currentTick, stopIds);
                // Re-schedule events since it's back in virtual control
                // We need to fetch estimator... ugly dependency issue here as estimator not
                // passed to returnToVirtual
                // We will defer scheduling to the next tick() call?
                // No, tick() only processes existing events. If queue is empty for this train,
                // it never moves.

                // We MUST schedule the next event here.
                // But we don't have the estimator.
                // Solution: We'll rely on the restored state's 'nextEventTick' (which is set to
                // current + buffer)
                // And just add that to queue.
                if (isWaiting) {
                    // It was waiting, so next is DEPARTURE
                    eventQueue.add(new TrainEvent(vt.getNextEventTick(), vt.getId(), EventType.DEPARTURE));
                } else {
                    // It was moving, so next is ARRIVAL
                    eventQueue.add(new TrainEvent(vt.getNextEventTick(), vt.getId(), EventType.ARRIVAL));
                }

                break;
            }
        }
    }

    /**
     * Completely remove a virtual train (e.g., when it completes a non-loop line).
     * A new one will be spawned at the start on the next initialize call.
     */
    public void removeVirtualTrain(UUID virtualTrainId) {
        materializedIds.remove(virtualTrainId);
        virtualTrains.remove(virtualTrainId);
        // We leave the events in the queue; they will be ignored in tick() because
        // virtualTrains.get(id) will return null.
    }

    /**
     * Get all virtual trains (for debugging/monitoring).
     */
    public List<VirtualTrain> getVirtualTrains() {
        return new ArrayList<>(virtualTrains.values());
    }

    /**
     * Get count of active (non-terminal) virtual trains.
     */
    public int getActiveCount(List<String> stopIds) {
        int count = 0;
        for (VirtualTrain vt : virtualTrains.values()) {
            if (!vt.isAtTerminal(stopIds)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Check if there are any available (non-materialized, non-terminal) virtual
     * trains.
     */
    public boolean hasAvailableTrains(List<String> stopIds) {
        for (VirtualTrain vt : virtualTrains.values()) {
            if (!materializedIds.contains(vt.getId()) && !vt.isAtTerminal(stopIds)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Refresh the topology of all virtual trains.
     * 
     * @param oldStopIds  The previous list of stop IDs
     * @param newStopIds  The new list of stop IDs
     * @param estimator   TravelTimeEstimator
     * @param currentTick Current server tick
     */
    public void refreshTopology(List<String> newStopIds, TravelTimeEstimator estimator,
            long currentTick) {
        // Use cachedStopIds as oldStopIds
        List<String> oldStopIds = new ArrayList<>(this.cachedStopIds);

        // Update cache
        this.cachedStopIds = new ArrayList<>(newStopIds);

        // Clear events as they are invalid
        eventQueue.clear();

        for (VirtualTrain vt : virtualTrains.values()) {
            // Apply sync
            vt.syncToNewTopology(oldStopIds, newStopIds, estimator, currentTick);

            // Re-schedule
            if (materializedIds.contains(vt.getId())) {
                continue; // Materialized trains handle themselves (or should be updated via physically
                          // checking?)
                // Actually if materialized, the physical train is authority.
                // When it returns to virtual, it will sync to NEW topology because
                // returnToVirtual will use new stopIds.
            } else {
                scheduleNextEvent(vt, estimator, currentTick);
            }
        }
    }

    public String getLineId() {
        return lineId;
    }

    @Override
    public String toString() {
        return String.format("VirtualTrainPool[line=%s, trains=%d, materialized=%d]",
                lineId, virtualTrains.size(), materializedIds.size());
    }
}
