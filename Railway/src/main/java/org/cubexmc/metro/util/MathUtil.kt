package org.cubexmc.metro.util

import org.bukkit.block.BlockFace
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Math utilities from BKCommonLib
 * Simplified version for Metro plugin use
 */
object MathUtil {

    private const val NORMALIZATION_EPSILON = 1e-10

    @JvmStatic
    fun floor(value: Double): Int {
        val i = value.toInt()
        return if (value < i) i - 1 else i
    }

    @JvmStatic
    fun ceil(value: Double): Int {
        val i = value.toInt()
        return if (value > i) i + 1 else i
    }

    @JvmStatic
    fun distance(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double): Double =
        sqrt(distanceSquared(x1, y1, z1, x2, y2, z2))

    @JvmStatic
    fun distanceSquared(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double): Double {
        val dx = x2 - x1
        val dy = y2 - y1
        val dz = z2 - z1
        return dx * dx + dy * dy + dz * dz
    }

    @JvmStatic
    fun getNormalizationFactor(x: Double, y: Double, z: Double): Double {
        val len = sqrt(x * x + y * y + z * z)
        return if (len < NORMALIZATION_EPSILON) Double.POSITIVE_INFINITY else 1.0 / len
    }

    @JvmStatic
    fun round(value: Double, decimals: Int): Double {
        val p = Math.pow(10.0, decimals.toDouble())
        return (value * p).roundToLong() / p
    }

    @JvmStatic
    fun clamp(value: Double, min: Double, max: Double): Double =
        if (value < min) min else if (value > max) max else value

    @JvmStatic
    fun clamp(value: Int, min: Int, max: Int): Int =
        if (value < min) min else if (value > max) max else value

    @JvmStatic
    fun isHeadingTo(dx: Double, dz: Double, direction: BlockFace): Boolean =
        isHeadingTo(direction.modX.toDouble(), direction.modZ.toDouble(), dx, dz)

    @JvmStatic
    fun isHeadingTo(cx: Double, cz: Double, dx: Double, dz: Double): Boolean = cx * dx + cz * dz > 0.0

    @JvmStatic
    fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t
}
