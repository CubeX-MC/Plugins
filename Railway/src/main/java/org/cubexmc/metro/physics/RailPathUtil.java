package org.cubexmc.metro.physics;

import java.util.EnumMap;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rail;
import org.bukkit.util.Vector;

/**
 * Simple rail geometry helper that mirrors TrainCarts' rail paths for vanilla
 * rail shapes.
 * Provides directional vectors and projection helpers that stay consistent with
 * how
 * TrainCarts walks rail segments.
 */
final class RailPathUtil {
    private static final double BASE_Y = 0.0625;
    // === Optimization: Cache PathSpecs for all rail shapes to avoid allocation ===
    private static final Map<Rail.Shape, PathSpec> CACHED_SPECS = new EnumMap<>(Rail.Shape.class);

    static {
        // Straight rails
        register(Rail.Shape.NORTH_SOUTH, new double[][] {
                { 0.5, BASE_Y, 0.0 },
                { 0.5, BASE_Y, 1.0 }
        });
        register(Rail.Shape.EAST_WEST, new double[][] {
                { 0.0, BASE_Y, 0.5 },
                { 1.0, BASE_Y, 0.5 }
        });

        // Curved rails
        register(Rail.Shape.NORTH_EAST, new double[][] {
                { 0.5, BASE_Y, 0.0 },
                { 0.75, BASE_Y, 0.25 },
                { 1.0, BASE_Y, 0.5 }
        });
        register(Rail.Shape.NORTH_WEST, new double[][] {
                { 0.5, BASE_Y, 0.0 },
                { 0.25, BASE_Y, 0.25 },
                { 0.0, BASE_Y, 0.5 }
        });
        register(Rail.Shape.SOUTH_EAST, new double[][] {
                { 0.5, BASE_Y, 1.0 },
                { 0.75, BASE_Y, 0.75 },
                { 1.0, BASE_Y, 0.5 }
        });
        register(Rail.Shape.SOUTH_WEST, new double[][] {
                { 0.5, BASE_Y, 1.0 },
                { 0.25, BASE_Y, 0.75 },
                { 0.0, BASE_Y, 0.5 }
        });

        // Ascending rails
        register(Rail.Shape.ASCENDING_EAST, new double[][] {
                { 0.0, BASE_Y, 0.5 },
                { 1.0, BASE_Y + 1.0, 0.5 }
        });
        register(Rail.Shape.ASCENDING_WEST, new double[][] {
                { 1.0, BASE_Y, 0.5 },
                { 0.0, BASE_Y + 1.0, 0.5 }
        });
        register(Rail.Shape.ASCENDING_NORTH, new double[][] {
                { 0.5, BASE_Y, 1.0 },
                { 0.5, BASE_Y + 1.0, 0.0 }
        });
        register(Rail.Shape.ASCENDING_SOUTH, new double[][] {
                { 0.5, BASE_Y, 0.0 },
                { 0.5, BASE_Y + 1.0, 1.0 }
        });
    }

    private RailPathUtil() {
        // Utility
    }

    private static void register(Rail.Shape shape, double[][] points) {
        CACHED_SPECS.put(shape, new PathSpec(points));
    }

    static Vector computeDirection(Location location, Vector fallback) {
        if (location == null)
            return fallback != null ? fallback.clone() : new Vector();

        Block block = location.getBlock();
        PathSpec spec = resolve(block);

        // Try block below if no rail found
        if (spec == null) {
            block = block.getRelative(0, -1, 0);
            spec = resolve(block);
        }

        if (spec == null) {
            return fallback != null ? fallback.clone() : new Vector();
        }

        Vector direction = spec.bestSegmentDirection(location, block);
        if (direction == null || direction.lengthSquared() < 1.0e-8) {
            return fallback != null ? fallback.clone() : new Vector();
        }
        if (fallback != null && fallback.lengthSquared() > 1.0e-8 && direction.dot(fallback) < 0.0) {
            direction.multiply(-1.0);
        }
        return direction.normalize();
    }

    static Location project(Location location) {
        if (location == null)
            return null;

        Block block = location.getBlock();
        PathSpec spec = resolve(block);

        if (spec == null) {
            block = block.getRelative(0, -1, 0);
            spec = resolve(block);
        }

        if (spec == null) {
            return location;
        }

        Vector projected = spec.project(location, block);
        return new Location(location.getWorld(), projected.getX(), projected.getY(), projected.getZ());
    }

    private static PathSpec resolve(Block block) {
        if (block == null)
            return null;
        Rail rail = railData(block);
        if (rail == null)
            return null;
        return CACHED_SPECS.get(rail.getShape());
    }

    private static Rail railData(Block block) {
        if (block == null)
            return null;
        BlockData data = block.getBlockData();
        if (data instanceof Rail) {
            return (Rail) data;
        }
        return null;
    }

    /**
     * Stateless path specification that calculates results on the fly
     * based on relative points and a block offset.
     */
    public static final class PathSpec {
        // Stores relative points: each is [x, y, z] relative to block origin
        private final double[][] relativePoints;

        PathSpec(double[][] points) {
            this.relativePoints = points;
        }

        public Vector bestSegmentDirection(Location location, Block block) {
            double locX = location.getX();
            double locY = location.getY();
            double locZ = location.getZ();

            double baseX = block.getX();
            double baseY = block.getY();
            double baseZ = block.getZ();

            double bestDistSq = Double.MAX_VALUE;
            double bestDirX = 0, bestDirY = 0, bestDirZ = 0;
            boolean found = false;

            for (int i = 0; i < relativePoints.length - 1; i++) {
                double[] p1 = relativePoints[i];
                double[] p2 = relativePoints[i + 1];

                // Calculate absolute segment
                double startX = baseX + p1[0];
                double startY = baseY + p1[1];
                double startZ = baseZ + p1[2];

                double endX = baseX + p2[0];
                double endY = baseY + p2[1];
                double endZ = baseZ + p2[2];

                // Segment vector
                double segX = endX - startX;
                double segY = endY - startY;
                double segZ = endZ - startZ;

                double lenSq = segX * segX + segY * segY + segZ * segZ;
                if (lenSq < 1.0e-8)
                    continue;

                // Project current location onto segment
                // t = (target - start) . seg / lenSq
                double t = ((locX - startX) * segX + (locY - startY) * segY + (locZ - startZ) * segZ) / lenSq;
                t = Math.max(0.0, Math.min(1.0, t));

                // Closest point on segment
                double projX = startX + segX * t;
                double projY = startY + segY * t;
                double projZ = startZ + segZ * t;

                double distSq = (projX - locX) * (projX - locX) +
                        (projY - locY) * (projY - locY) +
                        (projZ - locZ) * (projZ - locZ);

                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    bestDirX = segX;
                    bestDirY = segY;
                    bestDirZ = segZ;
                    found = true;
                }
            }

            return found ? new Vector(bestDirX, bestDirY, bestDirZ) : null;
        }

        public Vector project(Location location, Block block) {
            double locX = location.getX();
            double locY = location.getY();
            double locZ = location.getZ();

            double baseX = block.getX();
            double baseY = block.getY();
            double baseZ = block.getZ();

            double bestDistSq = Double.MAX_VALUE;
            double bestPtX = 0, bestPtY = 0, bestPtZ = 0;
            boolean found = false;

            for (int i = 0; i < relativePoints.length - 1; i++) {
                double[] p1 = relativePoints[i];
                double[] p2 = relativePoints[i + 1];

                double startX = baseX + p1[0];
                double startY = baseY + p1[1];
                double startZ = baseZ + p1[2];

                double endX = baseX + p2[0];
                double endY = baseY + p2[1];
                double endZ = baseZ + p2[2];

                double segX = endX - startX;
                double segY = endY - startY;
                double segZ = endZ - startZ;

                double lenSq = segX * segX + segY * segY + segZ * segZ;
                if (lenSq < 1.0e-8)
                    continue;

                double t = ((locX - startX) * segX + (locY - startY) * segY + (locZ - startZ) * segZ) / lenSq;
                t = Math.max(0.0, Math.min(1.0, t));

                double projX = startX + segX * t;
                double projY = startY + segY * t;
                double projZ = startZ + segZ * t;

                double distSq = (projX - locX) * (projX - locX) +
                        (projY - locY) * (projY - locY) +
                        (projZ - locZ) * (projZ - locZ);

                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    bestPtX = projX;
                    bestPtY = projY;
                    bestPtZ = projZ;
                    found = true;
                }
            }

            if (!found) {
                // Return last point as fallback
                double[] last = relativePoints[relativePoints.length - 1];
                return new Vector(baseX + last[0], baseY + last[1], baseZ + last[2]);
            }
            return new Vector(bestPtX, bestPtY, bestPtZ);
        }
    }
}
