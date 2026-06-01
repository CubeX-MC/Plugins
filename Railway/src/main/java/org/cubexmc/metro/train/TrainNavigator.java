package org.cubexmc.metro.train;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.entity.Minecart;
import org.bukkit.util.Vector;
import org.cubexmc.metro.model.Stop;
import org.cubexmc.metro.util.LocationUtil;
import org.cubexmc.metro.util.MinecartPhysicsUtil;

public class TrainNavigator {

    private final TrainInstance train;
    private final List<String> stopIds;

    private int currentIndex;
    private int targetIndex = -1;
    private String sectionKey;
    private Vector travelDirection;

    public TrainNavigator(TrainInstance train, List<String> stopIds) {
        this.train = train;
        this.stopIds = stopIds;
        this.currentIndex = 0;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public int getTargetIndex() {
        return targetIndex;
    }

    public void setCurrentIndex(int currentIndex) {
        this.currentIndex = currentIndex;
    }

    public void setTargetIndex(int targetIndex) {
        this.targetIndex = targetIndex;
    }

    public List<String> getStopIds() {
        return stopIds;
    }

    public String getSectionKey() {
        return sectionKey;
    }

    public Vector getTravelDirection() {
        return travelDirection;
    }

    public void setTravelDirection(Vector travelDirection) {
        this.travelDirection = travelDirection;
    }

    public void setSectionKey(String sectionKey) {
        this.sectionKey = sectionKey;
    }

    public void attemptDeparture(long currentTick) {
        TrainNavigatorDecisions.DepartureDecision departure = TrainNavigatorDecisions.resolveDeparture(
                stopIds.size(),
                currentIndex,
                targetIndex);
        if (departure.shouldTerminate) {
            beginTermination(currentTick);
            return;
        }
        targetIndex = departure.targetIndex;

        String fromId = stopIds.get(currentIndex);
        String toId = stopIds.get(targetIndex);

        Stop fromStop = train.getService().getStopManager().getStop(fromId);
        Stop toStop = train.getService().getStopManager().getStop(toId);
        if (fromStop == null || toStop == null || toStop.getStopPointLocation() == null) {
            beginTermination(currentTick);
            return;
        }

        String section = train.getService().buildSectionKey(fromId, toId);
        if (!train.getService().getBlockSectionManager().tryEnter(section)) {
            // Section already occupied -- another train (or a leaked reservation)
            // is holding it. Surface this to debug so a future "stuck train"
            // report can be diagnosed without spelunking.
            train.getService().getPlugin().debug("train_state_transitions",
                    "Departure blocked: section '" + section + "' is occupied; train "
                            + train.getId() + " staying in WAITING at " + fromId);
            return;
        }
        this.sectionKey = section;

        Vector boostDir = LocationUtil.vectorFromYaw(fromStop.getLaunchYaw());
        if (boostDir == null || boostDir.lengthSquared() < 1e-6) {
            boostDir = new Vector(0, 0, 1);
        }

        travelDirection = boostDir.normalize();

        train.applyInitialBoost(travelDirection);
        train.getPhysicsEngine().onDeparture(train, fromStop);
        train.publishDepartureEvents(fromStop, toStop);
        train.setState(TrainInstance.TrainState.MOVING, currentTick);
        train.setReadyToDepart(false);
        train.onSegmentStart(currentTick);
    }

    public void checkArrival(long currentTick) {
        if (targetIndex < 0 || targetIndex >= stopIds.size()) {
            return;
        }

        Stop targetStop = train.getService().getStopManager().getStop(stopIds.get(targetIndex));
        if (targetStop == null || targetStop.getStopPointLocation() == null) {
            return;
        }

        Minecart lead = train.getConsist().getLeadCar();
        if (lead == null || lead.isDead()) {
            return;
        }

        double distSq = lead.getLocation().distanceSquared(targetStop.getStopPointLocation());
        double leadSpeed = lead.getVelocity().length();

        if (ArrivalHeuristics.shouldArrive(distSq, leadSpeed)) {
            arriveAtStop(targetStop, currentTick);
        }
    }

    private void arriveAtStop(Stop stop, long currentTick) {
        TrainNavigatorDecisions.ArrivalDecision arrival = TrainNavigatorDecisions.resolveArrival(
                stopIds,
                targetIndex,
                stop.getId(),
                train.getService().isLoopLine());
        if (!arrival.valid) {
            return;
        }

        // Release the block-section reservation for the segment we just finished.
        // Without this the section stays "occupied" in BlockSectionManager forever
        // and any later train (or the same train re-traversing the segment on a
        // loop line) will fail tryEnter() on departure -- the symptom being
        // a freshly spawned train that sits at the platform and never leaves.
        // The parallel handleArrival/beginTermination/cleanup paths all release
        // here; this one was the only auto-arrival path missing it.
        if (sectionKey != null) {
            train.getService().getBlockSectionManager().leave(sectionKey);
            sectionKey = null;
        }

        train.setState(arrival.nextState, currentTick);
        currentIndex = arrival.currentIndex;
        targetIndex = arrival.targetIndex;
        train.setReadyToDepart(false);

        train.getConsist().zeroVelocity();
        travelDirection = null;

        if (stop.getStopPointLocation() != null) {
            Minecart lead = train.getConsist().getLeadCar();
            if (lead != null && !lead.isDead()) {
                float yaw = lead.getLocation().getYaw();
                Location snapLoc = stop.getStopPointLocation().clone();
                snapLoc.setYaw(yaw);
                snapLoc.setPitch(lead.getLocation().getPitch());
                lead.teleport(snapLoc);
                MinecartPhysicsUtil.forceVelocity(lead, new Vector(0, 0, 0), train.getService().getPlugin());
            }
        }

        train.getPhysicsEngine().onArrival(train, stop, currentTick);
        train.publishArrivalEvents(stop, arrival.terminal);

        train.tryRecordTravelTimeSample(currentTick);
    }

    public void handleArrival(Stop stop, long currentTick) {
        if (!train.isMoving()) {
            return;
        }

        String targetId = getTargetStopId();
        if (targetId == null || !targetId.equals(stop.getId())) {
            return;
        }

        train.getConsist().zeroVelocity();
        if (sectionKey != null) {
            train.getService().getBlockSectionManager().leave(sectionKey);
            sectionKey = null;
        }
        travelDirection = null;
        TrainNavigatorDecisions.ArrivalDecision arrival = TrainNavigatorDecisions.resolveArrival(
                stopIds,
                targetIndex,
                stop.getId(),
                train.getService().isLoopLine());
        if (!arrival.valid) {
            return;
        }

        train.setState(arrival.nextState, currentTick);
        train.setReadyToDepart(false);
        currentIndex = arrival.currentIndex;
        targetIndex = arrival.targetIndex;

        train.getPhysicsEngine().onArrival(train, stop, currentTick);
        train.publishArrivalEvents(stop, arrival.terminal);

        train.tryRecordTravelTimeSample(currentTick);
    }

    public void beginTermination(long currentTick) {
        if (sectionKey != null) {
            train.getService().getBlockSectionManager().leave(sectionKey);
            sectionKey = null;
        }
        travelDirection = null;
        train.setState(TrainInstance.TrainState.TERMINATING, currentTick);
    }

    public void cleanup() {
        if (sectionKey != null) {
            train.getService().getBlockSectionManager().leave(sectionKey);
            sectionKey = null;
        }
    }

    public String getTargetStopId() {
        if (targetIndex >= 0 && targetIndex < stopIds.size()) {
            return stopIds.get(targetIndex);
        }
        return null;
    }
}
