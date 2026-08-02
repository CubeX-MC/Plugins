package org.cubexmc.metro.util

import org.bukkit.util.Vector
import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Quaternion class for representing 3D rotations
 * Simplified version from BKCommonLib for Metro plugin use
 */
class Quaternion @JvmOverloads constructor(
    private var x: Double = 0.0,
    private var y: Double = 0.0,
    private var z: Double = 0.0,
    private var w: Double = 1.0,
) {

    fun getX(): Double = x

    fun getY(): Double = y

    fun getZ(): Double = z

    fun getW(): Double = w

    fun setTo(other: Quaternion) {
        x = other.x
        y = other.y
        z = other.z
        w = other.w
    }

    override fun toString(): String = "{x=$x, y=$y, z=$z, w=$w}"

    companion object {
        private const val LINEAR_INTERPOLATION_THRESHOLD = 0.9995

        @JvmStatic
        @Suppress("MagicNumber")
        fun fromLookDirection(direction: Vector, up: Vector): Quaternion {
            // Forward direction
            val forward = direction.clone().normalize()

            // Right direction (cross product of up and forward)
            val right = up.clone().crossProduct(forward).normalize()

            // Recalculate up to be orthogonal
            val upOrthogonal = forward.clone().crossProduct(right)

            // Build rotation matrix and convert to quaternion
            val m00 = right.x
            val m01 = right.y
            val m02 = right.z
            val m10 = upOrthogonal.x
            val m11 = upOrthogonal.y
            val m12 = upOrthogonal.z
            val m20 = forward.x
            val m21 = forward.y
            val m22 = forward.z

            val trace = m00 + m11 + m22
            val x: Double
            val y: Double
            val z: Double
            val w: Double

            if (trace > 0) {
                val s = 0.5 / sqrt(trace + 1.0)
                w = 0.25 / s
                x = (m21 - m12) * s
                y = (m02 - m20) * s
                z = (m10 - m01) * s
            } else if (m00 > m11 && m00 > m22) {
                val s = 2.0 * sqrt(1.0 + m00 - m11 - m22)
                w = (m21 - m12) / s
                x = 0.25 * s
                y = (m01 + m10) / s
                z = (m02 + m20) / s
            } else if (m11 > m22) {
                val s = 2.0 * sqrt(1.0 + m11 - m00 - m22)
                w = (m02 - m20) / s
                x = (m01 + m10) / s
                y = 0.25 * s
                z = (m12 + m21) / s
            } else {
                val s = 2.0 * sqrt(1.0 + m22 - m00 - m11)
                w = (m10 - m01) / s
                x = (m02 + m20) / s
                y = (m12 + m21) / s
                z = 0.25 * s
            }

            return Quaternion(x, y, z, w)
        }

        @JvmStatic
        fun slerp(q1: Quaternion, q2: Quaternion, t: Double): Quaternion {
            // Compute dot product
            var dot = q1.x * q2.x + q1.y * q2.y + q1.z * q2.z + q1.w * q2.w

            // If the dot product is negative, slerp won't take the shorter path
            // Fix by reversing one quaternion
            var x2 = q2.x
            var y2 = q2.y
            var z2 = q2.z
            var w2 = q2.w
            if (dot < 0.0) {
                dot = -dot
                x2 = -x2
                y2 = -y2
                z2 = -z2
                w2 = -w2
            }

            // Compute interpolation factors
            val factor1: Double
            val factor2: Double
            if (dot > LINEAR_INTERPOLATION_THRESHOLD) {
                // Quaternions are very close, use linear interpolation
                factor1 = 1.0 - t
                factor2 = t
            } else {
                // Spherical interpolation
                val angle = acos(dot)
                val sinAngle = sin(angle)
                factor1 = sin((1.0 - t) * angle) / sinAngle
                factor2 = sin(t * angle) / sinAngle
            }

            return Quaternion(
                q1.x * factor1 + x2 * factor2,
                q1.y * factor1 + y2 * factor2,
                q1.z * factor1 + z2 * factor2,
                q1.w * factor1 + w2 * factor2,
            )
        }
    }
}
