package org.cubexmc.metro.service.virtual;

import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.cubexmc.metro.estimation.TravelTimeEstimator;
import org.cubexmc.metro.util.LineTopologyUtil;
import org.cubexmc.metro.util.SchedulerUtil;
import org.cubexmc.metro.manager.StopManager;
import org.cubexmc.metro.model.Stop;

/**
 * Represents a virtual train that exists only in memory, tracking its position
 * along a line without spawning physical minecart entities.
 * 
 * Virtual trains continuously update their position based on travel time
 * estimates,
 * and can be materialized into physical TrainInstance when players are nearby.
 */
public class VirtualTrain {

    public enum State {
        WAITING, // Stopped at a station, waiting for dwell time
        MOVING // Traveling between stations
    }

    public enum EventType {
        ARRIVAL, // Arrived at a station (start of dwell)
        DEPARTURE // Departed from a station (start of travel)
    }

    private final UUID id;
    private final String lineId;
    private List<String> stopIds;
    private final int dwellTicks;
    private boolean isLoop;

    // DES State
    private long lastEventTick;
    private EventType lastEventType;
    private int currentStopIndex;
    private int targetStopIndex;

    // For interpolation
    private long nextEventTick;
    private double currentPathDurationSeconds; // For progress calculation

    /**
     * Create a new virtual train.
     * 
     * @param lineId           The line this train runs on
     * @param stopIds          Ordered list of stop IDs for the line
     * @param dwellTicks       Time to wait at each stop in ticks
     * @param initialStopIndex Initial position (stop index)
     * @param initialProgress  Initial progress (0-1, 0 = at stop, >0 = moving)
     * @param initialTick      Current tick when created
     */
    public VirtualTrain(String lineId, List<String> stopIds, int dwellTicks,
            int initialStopIndex, double initialProgress, long initialTick) {
        this.id = UUID.randomUUID();
        this.lineId = lineId;
        this.stopIds = new java.util.ArrayList<>(stopIds);
        this.dwellTicks = Math.max(20, dwellTicks);
        this.isLoop = LineTopologyUtil.isLoop(stopIds);

        this.currentStopIndex = Math.max(0, Math.min(initialStopIndex, stopIds.size() - 1));

        // Initialize state based on progress
        if (initialProgress > 0 && currentStopIndex < stopIds.size() - 1) {
            // In transit
            this.lastEventType = EventType.DEPARTURE;
            this.targetStopIndex = currentStopIndex + 1;
            this.lastEventTick = initialTick; // Approximate, will be fixed by first update
            // We'll need a first update to correct the nextEventTick
        } else {
            // Waiting at station
            this.lastEventType = EventType.ARRIVAL;
            this.targetStopIndex = -1;
            this.lastEventTick = initialTick;
        }

        // Initialize "next" fields to avoid NPEs before first simulation step
        this.nextEventTick = initialTick + 1;
        this.currentPathDurationSeconds = 10.0;
    }

    public UUID getId() {
        return id;
    }

    public String getLineId() {
        return lineId;
    }

    public State getState() {
        return lastEventType == EventType.ARRIVAL ? State.WAITING : State.MOVING;
    }

    public int getCurrentStopIndex() {
        return currentStopIndex;
    }

    public int getTargetStopIndex() {
        return targetStopIndex;
    }

    public double getSegmentProgress(long currentTick) {
        if (lastEventType == EventType.ARRIVAL) {
            // Waiting: progress depends on dwell time
            long elapsed = currentTick - lastEventTick;
            return Math.min(1.0, (double) elapsed / dwellTicks);
        } else {
            // Moving: progress depends on travel time
            long elapsed = currentTick - lastEventTick;
            if (currentPathDurationSeconds <= 0)
                return 0.0;
            double elapsedSeconds = elapsed / 20.0;
            return Math.min(1.0, elapsedSeconds / currentPathDurationSeconds);
        }
    }

    public String getCurrentStopId(List<String> stopIds) {
        if (currentStopIndex >= 0 && currentStopIndex < stopIds.size()) {
            return stopIds.get(currentStopIndex);
        }
        return null;
    }

    /**
     * Update the train state to a specific event.
     */
    public void onEvent(EventType type, int stopIndex, long eventTick, double nextDurationSeconds,
            List<String> stopIds) {
        this.lastEventType = type;
        this.currentStopIndex = stopIndex;
        this.lastEventTick = eventTick;
        this.currentPathDurationSeconds = nextDurationSeconds;

        if (type == EventType.ARRIVAL) {
            // Now waiting at station
            this.targetStopIndex = -1;
            // Next event will be DEPARTURE after dwellTicks
            this.nextEventTick = eventTick + dwellTicks;
        } else {
            // Now moving to next station
            // Determine target
            int next = stopIndex + 1;

            boolean isLoop = LineTopologyUtil.isLoop(stopIds);

            if (next >= stopIds.size()) {
                if (isLoop) {
                    next = 1; // 0 is same as N-1 in loop def usually, but let's stick to standard next
                    // Actually, for loop, we rely on the caller logic to wrap index
                }
            }
            this.targetStopIndex = next; // Warning: caller must ensure this is correct or we need logic here
            // Next event will be ARRIVAL after duration
            this.nextEventTick = eventTick + (long) (nextDurationSeconds * 20.0);
        }
    }

    /**
     * Sets the target stop index explicitely (used by the pool logic)
     */
    public void setTargetStopIndex(int index) {
        this.targetStopIndex = index;
    }

    public void setNextEventTick(long tick) {
        this.nextEventTick = tick;
    }

    public long getNextEventTick() {
        return nextEventTick;
    }

    public long getLastEventTick() {
        return lastEventTick;
    }

    public EventType getLastEventType() {
        return lastEventType;
    }

    public void initializeWaitingAt(int stopIndex, double elapsedDwellSeconds, long currentTick) {
        this.currentStopIndex = Math.max(0, Math.min(stopIndex, stopIds.size() - 1));
        this.targetStopIndex = -1;
        this.lastEventType = EventType.ARRIVAL;
        long elapsedTicks = Math.max(0L, Math.min(dwellTicks, Math.round(elapsedDwellSeconds * 20.0)));
        this.lastEventTick = currentTick - elapsedTicks;
        this.currentPathDurationSeconds = dwellTicks / 20.0;
        this.nextEventTick = Math.max(currentTick + 1L, this.lastEventTick + dwellTicks);
    }

    public void initializeMovingBetween(int fromIndex, int toIndex, double progress,
            double durationSeconds, long currentTick) {
        this.currentStopIndex = Math.max(0, Math.min(fromIndex, stopIds.size() - 1));
        this.targetStopIndex = Math.max(0, Math.min(toIndex, stopIds.size() - 1));
        this.lastEventType = EventType.DEPARTURE;
        this.currentPathDurationSeconds = Math.max(0.05, durationSeconds);
        double safeProgress = Math.max(0.0, Math.min(1.0, progress));
        long elapsedTicks = Math.max(0L, Math.round(this.currentPathDurationSeconds * 20.0 * safeProgress));
        this.lastEventTick = currentTick - elapsedTicks;
        this.nextEventTick = Math.max(currentTick + 1L,
                this.lastEventTick + Math.max(1L, Math.round(this.currentPathDurationSeconds * 20.0)));
    }

    /**
     * Estimate the ETA (in seconds) from current position to a specific stop.
     * 
     * @param stopIndex Target stop index
     * @param estimator Travel time estimator
     * @return Estimated seconds to reach the stop, or Double.POSITIVE_INFINITY if
     *         unreachable
     */
    public double estimateEtaToStop(int stopIndex, TravelTimeEstimator estimator, long currentTick) {
        if (stopIndex < 0 || stopIndex >= stopIds.size()) {
            return Double.POSITIVE_INFINITY;
        }

        // Already at or past this stop?
        if (lastEventType == EventType.ARRIVAL && currentStopIndex == stopIndex) {
            return 0.0;
        }

        // Behind us (non-loop) or too far ahead?
        if (!isLoop && stopIndex < currentStopIndex) {
            return Double.POSITIVE_INFINITY;
        }

        double eta = 0.0;

        if (lastEventType == EventType.DEPARTURE) {
            // Add remaining time in current segment
            // We trust nextEventTick to be the arrival time at targetStopIndex
            // ETA = nextEventTick - currentTick (converted to seconds)
            // But nextEventTick refers to Arrival at targetStopIndex

            // Wait, estimateEta is called with external time reference?
            // Usually assumes "now".
            // We need to pass currentTick or assume it's roughly "now"

            // The method signature uses estimator but not currentTick.
            // We should use System.currentTimeMillis or rely on lastEventTick
            // THIS IS TRICKY without currentTick.
            // Let's assume we are called "during" the tick process so we might need to
            // accept currentTick or use the queue time.

            // For now, let's use the stored state.
            // This is a limitation of the current API not passing currentTick to this
            // method.
            // We'll calculate purely based on stored path duration and progress.

            double remaining = 0;
            if (targetStopIndex >= 0) {
                String fromId = stopIds.get(currentStopIndex);
                String toId = stopIds.get(targetStopIndex);
                double segmentTotal = estimator.estimateSeconds(lineId, fromId, toId);
                // We don't have currentTick here!
                // Let's assume this method is only appropriate if we track progress externally
                // OR we can change the signature? No, LocalDispatchStrategy calls it.
                // We have to estimate "now" or store "lastTickedTime"

                // Actually, in DES, we don't update progress every tick.
                // So "how far is the train" is a function of (Now - lastEventTime).
                // We definitely need 'now'.
                // BUT: The caller (LocalDispatchStatement) does not pass 'now'.
                // I will ADD currentTick to the signature in LocalDispatchStrategy later?
                // Or I can use SchedulerUtil.getCurrentTick()? Yes, that's safe in main thread.
                // We'll use the passed currentTick parameter instead.

                long elapsed = currentTick - lastEventTick;
                double elapsedSeconds = elapsed / 20.0;
                remaining = Math.max(0, segmentTotal - elapsedSeconds);
            }

            eta += remaining;

            if (targetStopIndex == stopIndex) {
                return eta;
            }

            // Sum from target to destination
            int from = targetStopIndex;
            int to = stopIndex;
            eta += sumTravelTime(from, to, estimator);
        } else {
            // Waiting state: sum from current to destination
            // Remaining dwell time
            long elapsed = currentTick - lastEventTick;
            double remainingDwell = Math.max(0, (dwellTicks - elapsed) / 20.0);
            eta += remainingDwell;

            eta += sumTravelTime(currentStopIndex, stopIndex, estimator);
        }

        return eta;
    }

    private double sumTravelTime(int fromIndex, int toIndex, TravelTimeEstimator estimator) {
        // Simple iteration handling loop
        double sum = 0;
        int current = fromIndex;
        int safety = 0;

        while (current != toIndex && safety < stopIds.size() * 2) {
            if (current >= stopIds.size() - 1) {
                if (!isLoop)
                    break; // End of line
                // Loop
                // current = stopIds.size()-1 (Last).
                // Next is 1 (Standard cycle A->...->A->B is not right, usually A...A, next is
                // A->B which is 0->1)
                // If last stop is same as first stop (A), then we are at A. The next stop is B
                // (index 1).

                // wait, if stops are [A, B, C, A].
                // 0->1 (A->B), 1->2 (B->C), 2->3 (C->A).
                // At 3 (A), we are effectively at 0.
                // Next is 1.
                current = 0;
                // Don't add travel time for wrap-around as it's the same spatial point
            }

            int next = current + 1;
            if (next >= stopIds.size())
                break; // Should be handled above

            String fromId = stopIds.get(current);
            String toId = stopIds.get(next);

            sum += (dwellTicks / 20.0);
            sum += estimator.estimateSeconds(lineId, fromId, toId);

            current = next;
            safety++;
        }
        return sum;
    }

    /**
     * Estimate the current physical location of this virtual train.
     * Uses linear interpolation between stop locations.
     * 
     * @param stopManager Stop manager to look up stop locations
     * @return Estimated location, or null if cannot be determined
     */
    public Location estimateCurrentLocation(StopManager stopManager, List<String> stopIds) {
        if (lastEventType == EventType.ARRIVAL) {
            if (currentStopIndex < stopIds.size()) {
                Stop stop = stopManager.getStop(stopIds.get(currentStopIndex));
                return stop != null ? stop.getStopPointLocation() : null;
            }
            return null;
        }

        // Moving: interpolate between current and target stops
        Stop fromStop = stopManager.getStop(stopIds.get(currentStopIndex));
        Stop toStop = stopManager.getStop(stopIds.get(targetStopIndex));

        if (fromStop == null || toStop == null)
            return null;
        Location from = fromStop.getStopPointLocation();
        Location to = toStop.getStopPointLocation();
        if (from == null || to == null || from.getWorld() == null)
            return null;
        if (!from.getWorld().equals(to.getWorld()))
            return from;

        double t = getSegmentProgress(SchedulerUtil.getCurrentTick());
        return new Location(
                from.getWorld(),
                from.getX() + (to.getX() - from.getX()) * t,
                from.getY() + (to.getY() - from.getY()) * t,
                from.getZ() + (to.getZ() - from.getZ()) * t);
    }

    /**
     * Estimate the travel direction of this virtual train.
     * 
     * @param stopManager Stop manager to look up stop locations
     * @return Normalized direction vector, or null if cannot be determined
     */
    public Vector estimateTravelDirection(StopManager stopManager) {
        int fromIdx = currentStopIndex;
        int toIdx = (lastEventType == EventType.DEPARTURE && targetStopIndex >= 0)
                ? targetStopIndex
                : (currentStopIndex + 1 < stopIds.size() ? currentStopIndex + 1 : currentStopIndex);

        if (fromIdx == toIdx)
            return null;

        Stop fromStop = stopManager.getStop(stopIds.get(fromIdx));
        Stop toStop = stopManager.getStop(stopIds.get(toIdx));

        if (fromStop == null || toStop == null)
            return null;
        Location from = fromStop.getStopPointLocation();
        Location to = toStop.getStopPointLocation();
        if (from == null || to == null)
            return null;

        Vector dir = to.toVector().subtract(from.toVector());
        dir.setY(0);
        if (dir.lengthSquared() < 1e-6) {
            return null;
        }
        return dir.normalize();
    }

    /**
     * Check if this train is at or approaching a specific stop within a time
     * threshold.
     * 
     * @param stopIndex     Target stop index
     * @param maxEtaSeconds Maximum ETA in seconds
     * @param estimator     Travel time estimator
     * @return true if train is within threshold
     */
    public boolean isNearStop(int stopIndex, double maxEtaSeconds, TravelTimeEstimator estimator) {
        double eta = estimateEtaToStop(stopIndex, estimator, SchedulerUtil.getCurrentTick());
        return Double.isFinite(eta) && eta <= maxEtaSeconds;
    }

    /**
     * Restore state from a materialized train that is being virtualized back.
     * 
     * @param stopIndex   Current stop index
     * @param targetIndex Target stop index (-1 if waiting)
     * @param progress    Segment progress (0-1)
     * @param isWaiting   Whether the train was waiting
     * @param currentTick Current tick
     */
    public void restoreState(int stopIndex, int targetIndex, double progress,
            boolean isWaiting, long currentTick, List<String> stopIds) {
        this.currentStopIndex = Math.max(0, Math.min(stopIndex, stopIds.size() - 1));
        this.lastEventTick = currentTick;
        this.currentPathDurationSeconds = 10.0; // Default buffer

        if (isWaiting || targetIndex < 0) {
            this.lastEventType = EventType.ARRIVAL;
            this.targetStopIndex = -1;
            this.nextEventTick = currentTick + dwellTicks;
        } else {
            this.lastEventType = EventType.DEPARTURE;
            this.targetStopIndex = Math.max(0, Math.min(targetIndex, stopIds.size() - 1));
            // We don't have the estimator here to know true duration,
            // but the next event loop will correct it or we use default
            this.nextEventTick = currentTick + (long) (10.0 * 20.0);
        }
    }

    /**
     * Check if this virtual train has reached terminal and cannot proceed.
     * Only applies to non-loop lines.
     */
    /**
     * Check if the train is at terminal
     */
    public boolean isAtTerminal(List<String> stopIds) {
        boolean isLoop = LineTopologyUtil.isLoop(stopIds);
        if (isLoop)
            return false;
        return lastEventType == EventType.ARRIVAL && currentStopIndex >= stopIds.size() - 1;
    }

    /**
     * Tries to synchronize the train's logical position to a new topology.
     * 
     * @param oldStopIds  The previous list of stop IDs
     * @param newStopIds  The new list of stop IDs
     * @param estimator   TravelTimeEstimator to recalculate progress
     * @param currentTick Current server tick
     */
    public void syncToNewTopology(List<String> oldStopIds, List<String> newStopIds,
            TravelTimeEstimator estimator, long currentTick) {
        // 1. Identify where we are logically
        String currentStopId = null;
        if (currentStopIndex >= 0 && currentStopIndex < oldStopIds.size()) {
            currentStopId = oldStopIds.get(currentStopIndex);
        }

        String targetStopId = null;
        if (targetStopIndex >= 0 && targetStopIndex < oldStopIds.size()) {
            targetStopId = oldStopIds.get(targetStopIndex);
        }

        // 2. Map 'currentStopId' to new list
        int newCurrentIndex = -1;
        if (currentStopId != null) {
            // Find closest match or exact match
            newCurrentIndex = newStopIds.indexOf(currentStopId);
        }

        // If current stop was deleted, fallback to index? Or 0?
        if (newCurrentIndex == -1) {
            newCurrentIndex = Math.max(0, Math.min(currentStopIndex, newStopIds.size() - 1));
            // Reset state to waiting at this fallback stop
            onEvent(EventType.ARRIVAL, newCurrentIndex, currentTick, 0, newStopIds);
            return;
        }

        // Update cache
        this.stopIds = new java.util.ArrayList<>(newStopIds);
        this.isLoop = LineTopologyUtil.isLoop(stopIds);

        // 3. Handle based on event type
        if (lastEventType == EventType.ARRIVAL) {
            // We were waiting at currentStop.
            // Just update index.
            this.currentStopIndex = newCurrentIndex;
            // Target remains -1
            this.targetStopIndex = -1;
            // nextEventTick (DEPARTURE) remains valid usually, unless we want to force
            // re-calc?
            // It's just Time, so it's fine.
        } else {
            // We were moving from currentStop to targetStop
            // Map targetStop
            int newTargetIndex = -1;
            if (targetStopId != null) {
                newTargetIndex = newStopIds.indexOf(targetStopId);
            }

            this.currentStopIndex = newCurrentIndex;

            // If target is gone or changed
            if (newTargetIndex == -1) {
                // Target deleted.
                // Just target the next stop in new list from current?
                newTargetIndex = newCurrentIndex + 1;
                if (newTargetIndex >= newStopIds.size()) {
                    // Wrap or terminal
                    boolean isLoop = LineTopologyUtil.isLoop(newStopIds);
                    if (isLoop)
                        newTargetIndex = 1;
                    else
                        newTargetIndex = 0; // Turnaround?
                }

                // We need to re-estimate arrival time because segment changed
                String fromId = newStopIds.get(newCurrentIndex);
                String toId = newStopIds.get(newTargetIndex);
                double newDuration = estimator.estimateSeconds(lineId, fromId, toId);

                // Update event
                // We preserve 'elapsed' time?
                long elapsed = currentTick - lastEventTick;
                double elapsedSec = elapsed / 20.0;
                // New arrival = current + (newTotal - elapsed)
                // But wait, if elapsed > newTotal? We arrive immediately.
                double remaining = Math.max(0.5, newDuration - elapsedSec); // min 0.5s

                onEvent(EventType.DEPARTURE, newCurrentIndex, lastEventTick, newDuration, newStopIds);
                this.nextEventTick = currentTick + (long) (remaining * 20.0);
                this.targetStopIndex = newTargetIndex;

            } else {
                // Both Current and Target exist.
                // Check if any NEW stops inserted between them?
                // current in new: A (idx 5)
                // target in new: B (idx 7)
                // There is a stop C (idx 6) inserted!

                if (newTargetIndex > newCurrentIndex + 1) {
                    // There are stops in between!
                    // We must decide if we target C (6) or B (7).
                    // Depends on progress.

                    // Simple approach: Always target the IMMEDIATE next stop in new topology (C).
                    int interimTarget = newCurrentIndex + 1;
                    String interimId = newStopIds.get(interimTarget);

                    // Recalculate duration A->C
                    String fromId = newStopIds.get(newCurrentIndex);
                    double durationAC = estimator.estimateSeconds(lineId, fromId, interimId);

                    // How much have we traveled?
                    long elapsed = currentTick - lastEventTick;
                    double elapsedSec = elapsed / 20.0;

                    if (elapsedSec >= durationAC) {
                        // We technically passed C already.
                        // Teleport/Advance to C, depart from C to B?
                        // "Arrive" at C now?
                        // Let's set state: DEPARTURE from C, target B (or next interim).
                        // Effectively skipping dwell at C? Or forcing dwell?
                        // Desimulation simplification: Treat as departing C just now.

                        onEvent(EventType.DEPARTURE, interimTarget, currentTick,
                                estimator.estimateSeconds(lineId, interimId, newStopIds.get(interimTarget + 1)),
                                newStopIds);
                        // target is next next.. logic handles in onEvent partially but we need to
                        // verify target
                    } else {
                        // We are still before C. Retarget to C.
                        // Maintain start tick (A), but duration is now A->C.
                        onEvent(EventType.DEPARTURE, newCurrentIndex, lastEventTick, durationAC, newStopIds);
                        // Update next arrival
                        this.nextEventTick = lastEventTick + (long) (durationAC * 20.0);
                        this.targetStopIndex = interimTarget;
                    }
                } else {
                    // Adjacent. Just update indices.
                    this.targetStopIndex = newTargetIndex;
                }
            }
        }
    }

    @Override
    public String toString() {
        return String.format("VirtualTrain[%s, line=%s, event=%s, stop=%d->%d]",
                id.toString().substring(0, 8), lineId, lastEventType, currentStopIndex, targetStopIndex);
    }
}
