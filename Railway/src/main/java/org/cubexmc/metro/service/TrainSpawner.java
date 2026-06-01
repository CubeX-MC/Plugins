package org.cubexmc.metro.service;

import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Minecart;
import org.bukkit.util.Vector;
import org.cubexmc.metro.Metro;
import org.cubexmc.metro.model.Line;
import org.cubexmc.metro.model.Stop;
import org.cubexmc.metro.service.virtual.SpawnMode;
import org.cubexmc.metro.service.virtual.VirtualTrain;
import org.cubexmc.metro.train.TrainConsist;
import org.cubexmc.metro.train.TrainInstance;
import org.cubexmc.metro.util.LocationUtil;

/**
 * Handles all train spawning logic for a {@link LineService}.
 * Extracted from LineService to reduce its scope and improve testability.
 */
public class TrainSpawner {

    private final Metro plugin;
    private final LineService service;

    public TrainSpawner(Metro plugin, LineService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /**
     * Spawn a physical train at the first stop of the line (global mode).
     */
    public boolean spawnTrainAtFirstStop(long currentTick) {
        Line line = service.getLine();
        String lineId = service.getLineId();
        if (line == null) {
            plugin.getLogger().warning("Line " + lineId + " not found when spawning train.");
            return false;
        }

        List<String> stops = line.getOrderedStopIds();
        if (stops.size() < 2) {
            plugin.getLogger().warning("Line " + lineId + " needs at least two stops to spawn trains.");
            return false;
        }

        Stop startStop = plugin.getStopManager().getStop(stops.get(0));
        if (startStop == null || startStop.getStopPointLocation() == null) {
            plugin.getLogger().warning("Start stop for line " + lineId + " is not configured with a stop point.");
            return false;
        }

        TrainConsist consist = spawnConsist(line, startStop);
        if (consist.getCars().isEmpty()) {
            plugin.getLogger().warning("Failed to spawn minecart consist for line " + lineId + ".");
            return false;
        }

        TrainInstance train = new TrainInstance(service, line, consist, stops, currentTick, service.getDwellTicks());
        service.addTrain(train, currentTick);
        return true;
    }

    /**
     * Spawn a train for a virtual train, using the configured spawn mode.
     *
     * @return The spawned TrainInstance, or null if spawn failed
     */
    public TrainInstance spawnTrainForVirtual(long currentTick, int fromStopIndex, int toStopIndex,
            double progress, UUID virtualTrainId, String targetStopId) {
        Line line = service.getLine();
        String lineId = service.getLineId();
        if (line == null) return null;
        List<String> stops = line.getOrderedStopIds();
        if (stops.size() < 2) return null;

        SpawnMode mode = SpawnMode.from(plugin.getLocalSpawnMode(), SpawnMode.CURRENT_STOP);
        Location spawnLoc = null;
        float yaw = 0f;
        int effectiveFromIndex = fromStopIndex;

        switch (mode) {
            case MID_SEGMENT:
                spawnLoc = findMidSegmentSpawnLocation(stops, fromStopIndex, toStopIndex, progress);
                if (spawnLoc != null) {
                    yaw = computeYawBetweenStops(stops, fromStopIndex);
                    break;
                }
                // Fall through to PREVIOUS_STOP if mid-segment fails

            case PREVIOUS_STOP:
                Stop fromStop = plugin.getStopManager().getStop(stops.get(fromStopIndex));
                if (fromStop != null && fromStop.getStopPointLocation() != null) {
                    spawnLoc = LocationUtil.center(fromStop.getStopPointLocation().clone());
                    yaw = fromStop.getLaunchYaw();
                    effectiveFromIndex = fromStopIndex;
                }
                break;

            case PLATFORM_BOUNDARY:
                spawnLoc = findPlatformBoundarySpawnLocation(stops, targetStopId, fromStopIndex);
                if (spawnLoc != null) {
                    yaw = computeYawTowardsStop(targetStopId);
                } else {
                    Stop fallbackStop = plugin.getStopManager().getStop(stops.get(fromStopIndex));
                    if (fallbackStop != null && fallbackStop.getStopPointLocation() != null) {
                        spawnLoc = LocationUtil.center(fallbackStop.getStopPointLocation().clone());
                        yaw = fallbackStop.getLaunchYaw();
                    }
                }
                break;

            case CURRENT_STOP:
                Stop targetStop = plugin.getStopManager().getStop(targetStopId);
                if (targetStop != null && targetStop.getStopPointLocation() != null) {
                    spawnLoc = LocationUtil.center(targetStop.getStopPointLocation().clone());
                    yaw = targetStop.getLaunchYaw();
                    effectiveFromIndex = stops.indexOf(targetStopId);
                    if (effectiveFromIndex < 0) effectiveFromIndex = 0;
                }
                break;
        }

        if (spawnLoc == null) {
            plugin.getLogger().warning("Failed to find spawn location for line " + lineId + " in mode " + mode);
            return null;
        }

        // Ensure spawn location is on rail
        Location railLoc = findNearestRail(spawnLoc, plugin.getLocalRailSearchRadius());
        if (railLoc == null) {
            plugin.getLogger().warning("No rail found near spawn location for line " + lineId + " at " + spawnLoc);
            Stop firstStop = plugin.getStopManager().getStop(stops.get(0));
            if (firstStop != null && firstStop.getStopPointLocation() != null) {
                railLoc = LocationUtil.center(firstStop.getStopPointLocation().clone());
                yaw = firstStop.getLaunchYaw();
                effectiveFromIndex = 0;
            } else {
                return null;
            }
        } else {
            railLoc = LocationUtil.center(railLoc);
        }

        TrainConsist consist = spawnConsistAt(railLoc, yaw);
        if (consist.getCars().isEmpty()) {
            plugin.getLogger().warning("Failed to spawn minecart consist for line " + lineId);
            return null;
        }

        TrainInstance train = new TrainInstance(service, line, consist, stops, currentTick, service.getDwellTicks());
        train.setVirtualTrainId(virtualTrainId);

        if (mode == SpawnMode.CURRENT_STOP) {
            train.forceWaitingState(effectiveFromIndex, currentTick);
        } else if (mode == SpawnMode.PLATFORM_BOUNDARY && fromStopIndex == toStopIndex) {
            int prevIndex = effectiveFromIndex - 1;
            if (prevIndex < 0 && service.isLoopLine()) {
                prevIndex = stops.size() - 2;
            }
            Stop targetStop = plugin.getStopManager().getStop(targetStopId);
            if (targetStop != null && targetStop.getStopPointLocation() != null) {
                Vector spawnVec = train.getConsist().getLeadCar().getLocation().toVector();
                Vector targetVec = targetStop.getStopPointLocation().toVector();
                Vector distinctDir = targetVec.subtract(spawnVec);

                if (distinctDir.lengthSquared() > 0.001) {
                    distinctDir.normalize();
                    train.forceArrivingState(effectiveFromIndex, currentTick, distinctDir);
                    Minecart lead = consist.getLeadCar();
                    if (lead != null && !lead.isDead()) {
                        lead.setVelocity(distinctDir.clone().multiply(service.getCartSpeed()));
                    }
                } else {
                    train.forceArrivingState(effectiveFromIndex, currentTick);
                }
            } else {
                train.forceArrivingState(effectiveFromIndex, currentTick);
            }
        } else {
            train.adjustStartIndex(effectiveFromIndex, currentTick);
        }

        service.addTrain(train, currentTick);
        return train;
    }

    // ===== Private spawn helpers =====

    private TrainConsist spawnConsist(Line line, Stop startStop) {
        Location basePoint = LocationUtil.center(startStop.getStopPointLocation().clone());
        float yaw = startStop.getLaunchYaw();
        return spawnConsistAt(basePoint, yaw);
    }

    private TrainConsist spawnConsistAt(Location basePoint, float yaw) {
        TrainConsist consist = new TrainConsist();
        Vector direction = LocationUtil.vectorFromYaw(yaw);
        if (direction == null || direction.lengthSquared() == 0) {
            direction = new Vector(0, 0, 1);
        }
        int railSearchRadius = Math.max(2, plugin.getLocalRailSearchRadius());
        Location snappedBase = findNearestRail(basePoint, railSearchRadius);
        if (snappedBase != null) {
            basePoint = snappedBase;
        }
        Vector railDirection = LocationUtil.railDirection(basePoint, direction);
        if (railDirection != null && railDirection.lengthSquared() > 1.0e-6) {
            railDirection.setY(0);
            if (railDirection.lengthSquared() > 1.0e-6) {
                direction = railDirection.normalize();
            }
        }
        double spacing = service.getTrainSpacing();
        double maxSpeed = service.getCartSpeed();
        int trainCars = service.getTrainCars();

        for (int i = 0; i < trainCars; i++) {
            Location idealLoc = basePoint.clone().subtract(direction.clone().multiply(i * spacing));
            idealLoc.setYaw(yaw);
            Location spawnLoc = findNearestRail(idealLoc, railSearchRadius);
            if (spawnLoc == null) {
                spawnLoc = idealLoc;
            } else {
                spawnLoc.setYaw(yaw);
            }

            if (!LocationUtil.isRail(spawnLoc)) {
                Location nearRail = findNearestRail(spawnLoc, railSearchRadius);
                if (nearRail != null) {
                    spawnLoc = nearRail;
                    spawnLoc.setYaw(yaw);
                } else {
                    plugin.getLogger().warning("Spawn location for train car is not on rail at " + spawnLoc);
                    cleanupIncompleteConsist(consist);
                    return consist;
                }
            }

            Minecart cart = spawnLoc.getWorld().spawn(spawnLoc, Minecart.class, minecart -> {
                minecart.setCustomName(plugin.getTrainName());
                minecart.setCustomNameVisible(plugin.isTrainNameVisible());
                minecart.setSlowWhenEmpty(false);
                minecart.setMaxSpeed(maxSpeed);
                minecart.setGravity(false);
            });

            consist.addCar(cart);
        }

        if (consist.getCars().size() != trainCars) {
            cleanupIncompleteConsist(consist);
        }
        return consist;
    }

    private Location findMidSegmentSpawnLocation(List<String> stops, int fromIndex, int toIndex, double progress) {
        if (fromIndex < 0 || fromIndex >= stops.size() || toIndex < 0 || toIndex >= stops.size()) {
            return null;
        }
        Stop fromStop = plugin.getStopManager().getStop(stops.get(fromIndex));
        Stop toStop = plugin.getStopManager().getStop(stops.get(toIndex));
        if (fromStop == null || toStop == null) return null;

        Location from = fromStop.getStopPointLocation();
        Location to = toStop.getStopPointLocation();
        if (from == null || to == null || from.getWorld() == null) return null;
        if (!from.getWorld().equals(to.getWorld())) return null;

        double t = Math.max(0, Math.min(1, progress));
        Location interpolated = new Location(
                from.getWorld(),
                from.getX() + (to.getX() - from.getX()) * t,
                from.getY() + (to.getY() - from.getY()) * t,
                from.getZ() + (to.getZ() - from.getZ()) * t);

        return findNearestRail(interpolated, plugin.getLocalRailSearchRadius());
    }

    private Location findPlatformBoundarySpawnLocation(List<String> stops, String targetStopId, int fromIndex) {
        Stop targetStop = plugin.getStopManager().getStop(targetStopId);
        if (targetStop == null) return null;

        Location stopPoint = targetStop.getStopPointLocation();
        if (stopPoint == null) return null;

        Vector launchDir = LocationUtil.vectorFromYaw(targetStop.getLaunchYaw());
        if (launchDir == null || launchDir.lengthSquared() < 1e-6) {
            launchDir = new Vector(1, 0, 0);
        } else {
            launchDir = launchDir.normalize();
        }

        Location corner1 = targetStop.getCorner1();
        Location corner2 = targetStop.getCorner2();

        if (corner1 != null && corner2 != null && corner1.getWorld() != null
                && corner1.getWorld().equals(corner2.getWorld())) {
            double minX = Math.min(corner1.getX(), corner2.getX());
            double maxX = Math.max(corner1.getX(), corner2.getX());
            double minZ = Math.min(corner1.getZ(), corner2.getZ());
            double maxZ = Math.max(corner1.getZ(), corner2.getZ());

            double boundaryOffset = 3.0;
            Location entryPoint = stopPoint.clone();

            if (Math.abs(launchDir.getX()) > Math.abs(launchDir.getZ())) {
                if (launchDir.getX() > 0) {
                    entryPoint.setX(minX - boundaryOffset);
                } else {
                    entryPoint.setX(maxX + boundaryOffset);
                }
            } else {
                if (launchDir.getZ() > 0) {
                    entryPoint.setZ(minZ - boundaryOffset);
                } else {
                    entryPoint.setZ(maxZ + boundaryOffset);
                }
            }

            return findNearestRail(entryPoint, plugin.getLocalRailSearchRadius() + 3);
        }

        double distanceBack = 20.0;
        Location approxEntry = stopPoint.clone().subtract(launchDir.multiply(distanceBack));
        return findNearestRail(approxEntry, plugin.getLocalRailSearchRadius());
    }

    private float computeYawBetweenStops(List<String> stops, int fromIndex) {
        if (fromIndex < 0 || fromIndex >= stops.size()) return 0f;
        Stop fromStop = plugin.getStopManager().getStop(stops.get(fromIndex));
        return fromStop != null ? fromStop.getLaunchYaw() : 0f;
    }

    private float computeYawTowardsStop(String toStopId) {
        Stop toStop = plugin.getStopManager().getStop(toStopId);
        return toStop != null ? toStop.getLaunchYaw() : 0f;
    }

    private Location findNearestRail(Location center, int radius) {
        return LocationUtil.findNearestRail(center, Math.max(0, radius));
    }

    private void cleanupIncompleteConsist(TrainConsist consist) {
        for (Minecart cart : consist.getCars()) {
            if (cart != null && !cart.isDead()) {
                cart.remove();
            }
        }
        consist.clear();
    }
}
