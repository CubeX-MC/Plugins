package org.cubexmc.metro.physics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Minecart;
import org.bukkit.util.Vector;

final class KinematicTrailBuffer {

    static final class TrailPoint {
        final double x;
        final double y;
        final double z;
        final double vx;
        final double vy;
        final double vz;
        final double cumulativeDistance;

        TrailPoint(double x, double y, double z, double vx, double vy, double vz, double cumulativeDistance) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.cumulativeDistance = cumulativeDistance;
        }
    }

    static final class TrailSample {
        final Location location;
        final Vector tangent;
        final double speed;

        TrailSample(Location location, Vector tangent, double speed) {
            this.location = location;
            this.tangent = tangent;
            this.speed = speed;
        }
    }

    private final ArrayDeque<TrailPoint> trail = new ArrayDeque<>();

    void clear() {
        trail.clear();
    }

    boolean isEmpty() {
        return trail.isEmpty();
    }

    int size() {
        return trail.size();
    }

    void maintain(int carCount, double spacing) {
        if (trail.isEmpty()) {
            return;
        }
        TrailPoint head = trail.peekFirst();
        if (head == null) {
            return;
        }
        double maxDistance = (Math.max(1, carCount) + 3) * Math.max(0.1, spacing) + 12.0;
        while (trail.size() > 2) {
            TrailPoint tail = trail.peekLast();
            if (tail == null) {
                break;
            }
            double storedDistance = head.cumulativeDistance - tail.cumulativeDistance;
            if (storedDistance <= maxDistance) {
                break;
            }
            trail.removeLast();
        }
        while (trail.size() > 800) {
            trail.removeLast();
        }
    }

    void addPoint(double x, double y, double z, double vx, double vy, double vz) {
        double cumulativeDistance = 0.0;

        if (!trail.isEmpty()) {
            TrailPoint last = trail.peekFirst();
            double dx = x - last.x;
            double dy = y - last.y;
            double dz = z - last.z;
            double sectionDistance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (sectionDistance < 1.0e-4) {
                return;
            }
            cumulativeDistance = last.cumulativeDistance + sectionDistance;
        }

        trail.addFirst(new TrailPoint(x, y, z, vx, vy, vz, cumulativeDistance));
        while (trail.size() > 900) {
            trail.removeLast();
        }
    }

    void seedFromConsist(List<Minecart> cars, Vector leadVelocity) {
        if (cars == null || cars.isEmpty()) {
            return;
        }

        Minecart lead = cars.get(0);
        if (lead == null || lead.isDead()) {
            return;
        }

        Location leadProjected = RailPathUtil.project(lead.getLocation());
        if (leadProjected == null) {
            leadProjected = lead.getLocation();
        }

        List<TrailPoint> points = new ArrayList<>();
        double cumulativeDistance = 0.0;
        points.add(new TrailPoint(
                leadProjected.getX(), leadProjected.getY(), leadProjected.getZ(),
                leadVelocity.getX(), leadVelocity.getY(), leadVelocity.getZ(),
                cumulativeDistance));

        Location previousLocation = leadProjected.clone();
        Vector fallbackVelocity = leadVelocity.clone();
        for (int i = 1; i < cars.size(); i++) {
            Minecart car = cars.get(i);
            if (car == null || car.isDead()) {
                continue;
            }

            Location projected = RailPathUtil.project(car.getLocation());
            if (projected == null) {
                projected = car.getLocation();
            }

            double gap = projected.distance(previousLocation);
            if (gap > 1.0e-4) {
                cumulativeDistance -= gap;
            }

            points.add(new TrailPoint(
                    projected.getX(), projected.getY(), projected.getZ(),
                    fallbackVelocity.getX(), fallbackVelocity.getY(), fallbackVelocity.getZ(),
                    cumulativeDistance));
            previousLocation = projected;
        }

        trail.clear();
        for (int index = points.size() - 1; index >= 0; index--) {
            trail.addFirst(points.get(index));
        }
    }

    TrailSample sampleState(double distanceBehind, World world, Vector fallbackDirection) {
        TrailPoint point = samplePointAt(distanceBehind);
        if (point == null || world == null) {
            return null;
        }

        Location location = new Location(world, point.x, point.y, point.z);
        Vector tangent = computeTangent(distanceBehind);
        double speed = Math.sqrt(point.vx * point.vx + point.vy * point.vy + point.vz * point.vz);

        if ((tangent == null || tangent.lengthSquared() < 1.0e-8) && speed > 1.0e-6) {
            tangent = new Vector(point.vx, point.vy, point.vz);
        }
        if ((tangent == null || tangent.lengthSquared() < 1.0e-8) && fallbackDirection != null) {
            tangent = fallbackDirection.clone();
        }
        if (tangent == null || tangent.lengthSquared() < 1.0e-8) {
            tangent = new Vector(1, 0, 0);
        }

        return new TrailSample(location, tangent, speed);
    }

    TrailPoint samplePointAt(double distanceBehind) {
        if (trail.isEmpty()) {
            return null;
        }

        TrailPoint head = trail.peekFirst();
        double targetDistance = head.cumulativeDistance - distanceBehind;
        if (targetDistance < 0) {
            return trail.peekLast();
        }

        TrailPoint previous = head;
        for (TrailPoint point : trail) {
            if (point.cumulativeDistance <= targetDistance) {
                double segmentDistance = previous.cumulativeDistance - point.cumulativeDistance;
                if (segmentDistance < 1.0e-6) {
                    return previous;
                }

                double t = (targetDistance - point.cumulativeDistance) / segmentDistance;
                t = Math.max(0.0, Math.min(1.0, t));
                return new TrailPoint(
                        point.x + t * (previous.x - point.x),
                        point.y + t * (previous.y - point.y),
                        point.z + t * (previous.z - point.z),
                        point.vx + t * (previous.vx - point.vx),
                        point.vy + t * (previous.vy - point.vy),
                        point.vz + t * (previous.vz - point.vz),
                        targetDistance);
            }
            previous = point;
        }

        return trail.peekLast();
    }

    Vector computeTangent(double distanceBehind) {
        double delta = Math.max(0.05, Math.min(0.75, (distanceBehind * 0.5) + 0.1));
        double aheadDistance = Math.max(0.0, distanceBehind - delta);
        double behindDistance = distanceBehind + delta;
        TrailPoint ahead = samplePointAt(aheadDistance);
        TrailPoint behind = samplePointAt(behindDistance);
        if (ahead == null || behind == null) {
            TrailPoint center = samplePointAt(distanceBehind);
            return center != null ? new Vector(center.vx, center.vy, center.vz) : null;
        }

        Vector tangent = new Vector(ahead.x - behind.x, ahead.y - behind.y, ahead.z - behind.z);
        if (tangent.lengthSquared() < 1.0e-8) {
            TrailPoint center = samplePointAt(distanceBehind);
            if (center != null) {
                tangent = new Vector(center.vx, center.vy, center.vz);
            }
        }
        return tangent;
    }
}