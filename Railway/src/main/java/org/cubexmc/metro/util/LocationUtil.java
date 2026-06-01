package org.cubexmc.metro.util;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.data.Rail;
import org.bukkit.util.Vector;

import java.util.EnumSet;
import java.util.Set;

public class LocationUtil {

    public enum RailType {
        STRAIGHT,
        CURVE,
        ASCENDING,
        DESCENDING,
        UNKNOWN
    }

    private static final Set<Material> RAIL_MATERIALS = EnumSet.of(
        Material.RAIL,
        Material.POWERED_RAIL,
        Material.DETECTOR_RAIL,
        Material.ACTIVATOR_RAIL
    );

    public static String locationToString(Location location) {
        if (location == null || location.getWorld() == null) return null;
        return String.format("%s,%d,%d,%d",
                location.getWorld().getName(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ());
    }

    public static Location locationFromString(String world, String locationString) {
        if (locationString == null) return null;
        String[] parts = locationString.split(",");
        if (parts.length != 4) return null;
        try {
            double x = Double.parseDouble(parts[1]);
            double y = Double.parseDouble(parts[2]);
            double z = Double.parseDouble(parts[3]);
            return new Location(
                    org.bukkit.Bukkit.getWorld(world != null ? world : parts[0]),
                    x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean isSameLocation(Location loc1, Location loc2) {
        if (loc1 == null || loc2 == null) return false;
        return loc1.getWorld() != null && loc1.getWorld().equals(loc2.getWorld())
                && loc1.getBlockX() == loc2.getBlockX()
                && loc1.getBlockY() == loc2.getBlockY()
                && loc1.getBlockZ() == loc2.getBlockZ();
    }

    public static Vector getDirectionVector(Location from, Location to) {
        return to.toVector().subtract(from.toVector()).normalize();
    }

    public static boolean isRail(Location location) {
        if (location == null) return false;
        Block block = location.getBlock();
        if (block != null && isRailBlock(block.getType())) return true;
        if (block != null) {
            Block below = block.getRelative(0, -1, 0);
            if (below != null) return isRailBlock(below.getType());
        }
        return false;
    }

    public static boolean isOnRail(Location location) {
        return isRail(location);
    }

    private static boolean isRailBlock(Material material) {
        return material != null && material.name().contains("RAIL");
    }

    public static boolean isPoweredAscendingRailPowered(Location location) {
        if (location == null) return false;
        Rail rail = getRailData(location.getBlock());
        if (rail == null) rail = getRailData(location.getBlock().getRelative(0, -1, 0));
        if (rail == null) return false;
        Rail.Shape shape = rail.getShape();
        boolean ascending = shape == Rail.Shape.ASCENDING_EAST || shape == Rail.Shape.ASCENDING_WEST
                || shape == Rail.Shape.ASCENDING_NORTH || shape == Rail.Shape.ASCENDING_SOUTH;
        if (!ascending) return false;
        if (rail instanceof Powerable) return ((Powerable) rail).isPowered();
        return false;
    }

    private static Rail getRailData(Block block) {
        if (block == null) return null;
        BlockData data = block.getBlockData();
        return data instanceof Rail ? (Rail) data : null;
    }

    public static boolean isCurveRail(Location location) {
        Rail rail = getRailData(location != null ? location.getBlock() : null);
        if (rail == null) rail = getRailData(location != null ? location.getBlock().getRelative(0, -1, 0) : null);
        if (rail == null) return false;
        switch (rail.getShape()) {
            case NORTH_EAST: case NORTH_WEST: case SOUTH_EAST: case SOUTH_WEST:
                return true;
            default:
                return false;
        }
    }

    private static Location computeRailCenter(Block block, Rail rail) {
        Location center = new Location(block.getWorld(), block.getX() + 0.5, block.getY(), block.getZ() + 0.5);
        switch (rail.getShape()) {
            case ASCENDING_EAST: case ASCENDING_WEST: case ASCENDING_NORTH: case ASCENDING_SOUTH:
                center.add(0.0, 0.5, 0.0);
                break;
            default:
                break;
        }
        return center;
    }

    private static Location centerRail(Location candidate) {
        if (candidate == null) return candidate;
        Block block = candidate.getBlock();
        Rail rail = getRailData(block);
        if (rail != null) return computeRailCenter(block, rail);
        Block below = block.getRelative(0, -1, 0);
        rail = getRailData(below);
        if (rail != null) return computeRailCenter(below, rail);
        return center(candidate);
    }

    public static RailType getRailType(Location location) {
        if (location == null) return RailType.UNKNOWN;
        Block block = location.getBlock();
        BlockData data = block.getBlockData();
        if (!(data instanceof Rail)) {
            Block below = block.getRelative(0, -1, 0);
            if (below.getBlockData() instanceof Rail) data = below.getBlockData();
            else return RailType.UNKNOWN;
        }
        Rail rail = (Rail) data;
        switch (rail.getShape()) {
            case NORTH_SOUTH: case EAST_WEST: return RailType.STRAIGHT;
            case NORTH_EAST: case NORTH_WEST: case SOUTH_EAST: case SOUTH_WEST: return RailType.CURVE;
            case ASCENDING_EAST: case ASCENDING_WEST: case ASCENDING_NORTH: case ASCENDING_SOUTH: return RailType.ASCENDING;
            default: return RailType.UNKNOWN;
        }
    }

    public static double getSafeSpeedForRail(RailType type, double baseSpeed, boolean safeMode) {
        if (!safeMode) return baseSpeed;
        switch (type) {
            case STRAIGHT: return baseSpeed;
            case CURVE: return Math.min(baseSpeed * 0.65, 0.25);
            case ASCENDING: return Math.min(baseSpeed * 0.87, 0.35);
            case DESCENDING: return Math.min(baseSpeed * 0.95, 0.42);
            default: return Math.min(baseSpeed * 0.80, 0.32);
        }
    }

    public static Vector vectorFromYaw(float yaw) {
        double radians = Math.toRadians(yaw);
        double x = -Math.sin(radians);
        double z = Math.cos(radians);
        Vector vector = new Vector(x, 0, z);
        return vector.lengthSquared() == 0 ? new Vector(0, 0, 0) : vector.normalize();
    }

    public static Location center(Location location) {
        if (location == null) return null;
        double x = location.getX(), z = location.getZ();
        if (Math.abs(x - Math.floor(x)) < 1e-6 && Math.abs(z - Math.floor(z)) < 1e-6) {
            return location.clone().add(0.5, 0, 0.5);
        }
        return location.clone();
    }

    public static Location snapToRail(Location location, org.bukkit.World world) {
        if (location == null || world == null) return location;
        Location best = null;
        double bestDist = Double.MAX_VALUE;
        int bx = location.getBlockX(), by = location.getBlockY(), bz = location.getBlockZ();
        for (int y = -1; y <= 1; y++)
            for (int x = -1; x <= 1; x++)
                for (int z = -1; z <= 1; z++) {
                    Location c = new Location(world, bx + x, by + y, bz + z);
                    if (isRail(c)) {
                        Location center = centerRail(c);
                        double d = center.distanceSquared(location);
                        if (d < bestDist) { bestDist = d; best = center; }
                    }
                }
        return best != null ? best : location;
    }

    public static Location findNearestRail(Location location, double radius) {
        if (location == null || location.getWorld() == null) return null;
        Location best = null;
        double bestDistSq = radius * radius;
        int r = (int) Math.ceil(radius), bx = location.getBlockX(), by = location.getBlockY(), bz = location.getBlockZ();
        for (int dx = -r; dx <= r; dx++)
            for (int dy = -r; dy <= r; dy++)
                for (int dz = -r; dz <= r; dz++) {
                    Location c = new Location(location.getWorld(), bx + dx, by + dy, bz + dz);
                    if (isRail(c)) {
                        Location center = centerRail(c);
                        double ds = center.distanceSquared(location);
                        if (ds < bestDistSq) { bestDistSq = ds; best = center; }
                    }
                }
        return best;
    }

    public static Vector railDirection(Location location, Vector fallback) {
        if (location == null) return fallback;
        Block block = location.getBlock();
        BlockData data = block.getBlockData();
        Rail rail = null;
        if (data instanceof Rail) rail = (Rail) data;
        else {
            Block below = block.getRelative(0, -1, 0);
            if (below.getBlockData() instanceof Rail) rail = (Rail) below.getBlockData();
        }
        if (rail != null) {
            Vector dir = vectorFromRailShape(rail.getShape(), location, fallback);
            if (dir != null) {
                Rail.Shape shape = rail.getShape();
                if (shape != Rail.Shape.NORTH_EAST && shape != Rail.Shape.NORTH_WEST
                        && shape != Rail.Shape.SOUTH_EAST && shape != Rail.Shape.SOUTH_WEST) {
                    if (fallback != null && dir.dot(fallback) < 0) dir.multiply(-1);
                }
                return dir;
            }
        }
        return fallback;
    }

    private static Vector vectorFromRailShape(Rail.Shape shape, Location location, Vector fallback) {
        if (shape == null) return null;
        switch (shape) {
            case NORTH_SOUTH: return new Vector(0, 0, 1);
            case EAST_WEST: return new Vector(1, 0, 0);
            case ASCENDING_EAST: return new Vector(1, 1, 0);
            case ASCENDING_WEST: return new Vector(-1, 1, 0);
            case ASCENDING_NORTH: return new Vector(0, 1, -1);
            case ASCENDING_SOUTH: return new Vector(0, 1, 1);
            case SOUTH_EAST:
                return computeCurveDirection(location, fallback, new Vector(0, 0, 1), new Vector(1, 0, 0));
            case SOUTH_WEST:
                return computeCurveDirection(location, fallback, new Vector(0, 0, 1), new Vector(-1, 0, 0));
            case NORTH_WEST:
                return computeCurveDirection(location, fallback, new Vector(0, 0, -1), new Vector(-1, 0, 0));
            case NORTH_EAST:
                return computeCurveDirection(location, fallback, new Vector(0, 0, -1), new Vector(1, 0, 0));
            default: return null;
        }
    }

    private static Vector computeCurveDirection(Location location, Vector fallback, Vector dir1, Vector dir2) {
        double dx = location.getX() - Math.floor(location.getX()) - 0.5;
        double dz = location.getZ() - Math.floor(location.getZ()) - 0.5;
        Vector radial;
        if (Math.abs(dx) < 1e-3 && Math.abs(dz) < 1e-3) {
            Vector fb = (fallback != null) ? fallback.clone() : new Vector(1, 0, 0);
            if (fb.lengthSquared() < 1e-6) fb = new Vector(1, 0, 0);
            fb.setY(0); fb.normalize();
            radial = new Vector(-fb.getX(), 0, -fb.getZ());
        } else {
            radial = new Vector(dx, 0, dz);
        }
        Vector cw = new Vector(radial.getZ(), 0, -radial.getX());
        Vector ccw = new Vector(-radial.getZ(), 0, radial.getX());
        if (fallback == null || fallback.lengthSquared() < 1e-6) {
            Vector avg = dir1.clone().add(dir2).normalize();
            return (cw.dot(avg) >= ccw.dot(avg) ? cw : ccw).normalize();
        }
        return (cw.dot(fallback) >= ccw.dot(fallback) ? cw : ccw).normalize();
    }
}
