package org.cubexmc.metro.util;

/**
 * Math utilities from BKCommonLib
 * Simplified version for Metro plugin use
 */
public class MathUtil {

    public static int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }

    public static int ceil(double value) {
        int i = (int) value;
        return value > i ? i + 1 : i;
    }

    public static double distance(double x1, double y1, double z1, double x2, double y2, double z2) {
        return Math.sqrt(distanceSquared(x1, y1, z1, x2, y2, z2));
    }

    public static double distanceSquared(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        return dx * dx + dy * dy + dz * dz;
    }

    public static double getNormalizationFactor(double x, double y, double z) {
        double len = Math.sqrt(x * x + y * y + z * z);
        return len < 1e-10 ? Double.POSITIVE_INFINITY : 1.0 / len;
    }

    public static double round(double value, int decimals) {
        double p = Math.pow(10, decimals);
        return Math.round(value * p) / p;
    }

    public static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }

    public static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    public static boolean isHeadingTo(double dx, double dz, org.bukkit.block.BlockFace direction) {
        return isHeadingTo(direction.getModX(), direction.getModZ(), dx, dz);
    }

    public static boolean isHeadingTo(double cx, double cz, double dx, double dz) {
        return cx * dx + cz * dz > 0.0;
    }

    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
