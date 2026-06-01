package org.cubexmc.metro.util;

import org.bukkit.util.Vector;

/**
 * Quaternion class for representing 3D rotations
 * Simplified version from BKCommonLib for Metro plugin use
 */
public class Quaternion {
    private double x, y, z, w;

    public Quaternion() {
        this(0.0, 0.0, 0.0, 1.0);
    }

    public Quaternion(double x, double y, double z, double w) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.w = w;
    }

    public double getX() {
        return this.x;
    }

    public double getY() {
        return this.y;
    }

    public double getZ() {
        return this.z;
    }

    public double getW() {
        return this.w;
    }

    public void setTo(Quaternion other) {
        this.x = other.x;
        this.y = other.y;
        this.z = other.z;
        this.w = other.w;
    }

    public static Quaternion fromLookDirection(Vector direction, Vector up) {
        // Forward direction
        Vector forward = direction.clone().normalize();
        
        // Right direction (cross product of up and forward)
        Vector right = up.clone().crossProduct(forward).normalize();
        
        // Recalculate up to be orthogonal
        Vector upOrthogonal = forward.clone().crossProduct(right);
        
        // Build rotation matrix and convert to quaternion
        double m00 = right.getX();
        double m01 = right.getY();
        double m02 = right.getZ();
        double m10 = upOrthogonal.getX();
        double m11 = upOrthogonal.getY();
        double m12 = upOrthogonal.getZ();
        double m20 = forward.getX();
        double m21 = forward.getY();
        double m22 = forward.getZ();
        
        double trace = m00 + m11 + m22;
        double x, y, z, w;
        
        if (trace > 0) {
            double s = 0.5 / Math.sqrt(trace + 1.0);
            w = 0.25 / s;
            x = (m21 - m12) * s;
            y = (m02 - m20) * s;
            z = (m10 - m01) * s;
        } else if (m00 > m11 && m00 > m22) {
            double s = 2.0 * Math.sqrt(1.0 + m00 - m11 - m22);
            w = (m21 - m12) / s;
            x = 0.25 * s;
            y = (m01 + m10) / s;
            z = (m02 + m20) / s;
        } else if (m11 > m22) {
            double s = 2.0 * Math.sqrt(1.0 + m11 - m00 - m22);
            w = (m02 - m20) / s;
            x = (m01 + m10) / s;
            y = 0.25 * s;
            z = (m12 + m21) / s;
        } else {
            double s = 2.0 * Math.sqrt(1.0 + m22 - m00 - m11);
            w = (m10 - m01) / s;
            x = (m02 + m20) / s;
            y = (m12 + m21) / s;
            z = 0.25 * s;
        }
        
        return new Quaternion(x, y, z, w);
    }

    public static Quaternion slerp(Quaternion q1, Quaternion q2, double t) {
        // Compute dot product
        double dot = q1.x * q2.x + q1.y * q2.y + q1.z * q2.z + q1.w * q2.w;
        
        // If the dot product is negative, slerp won't take the shorter path
        // Fix by reversing one quaternion
        double x2 = q2.x, y2 = q2.y, z2 = q2.z, w2 = q2.w;
        if (dot < 0.0) {
            dot = -dot;
            x2 = -x2;
            y2 = -y2;
            z2 = -z2;
            w2 = -w2;
        }
        
        // Compute interpolation factors
        double factor1, factor2;
        if (dot > 0.9995) {
            // Quaternions are very close, use linear interpolation
            factor1 = 1.0 - t;
            factor2 = t;
        } else {
            // Spherical interpolation
            double angle = Math.acos(dot);
            double sinAngle = Math.sin(angle);
            factor1 = Math.sin((1.0 - t) * angle) / sinAngle;
            factor2 = Math.sin(t * angle) / sinAngle;
        }
        
        return new Quaternion(
            q1.x * factor1 + x2 * factor2,
            q1.y * factor1 + y2 * factor2,
            q1.z * factor1 + z2 * factor2,
            q1.w * factor1 + w2 * factor2
        );
    }

    @Override
    public String toString() {
        return "{x=" + x + ", y=" + y + ", z=" + z + ", w=" + w + "}";
    }
}
