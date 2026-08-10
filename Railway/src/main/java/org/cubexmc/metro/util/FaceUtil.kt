package org.cubexmc.metro.util

import org.bukkit.block.BlockFace
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Utility functions from TrainCarts
 */
object FaceUtil {

    private const val EPSILON = 1e-6
    private const val FULL_CIRCLE_DEGREES = 360.0

    @JvmStatic
    @Suppress("MagicNumber", "ReturnCount")
    fun vecToFace(motX: Double, motY: Double, motZ: Double, useSubCardinal: Boolean): BlockFace {
        // Simplify to horizontal direction for minecarts
        val absX = abs(motX)
        val absZ = abs(motZ)

        if (absX < EPSILON && absZ < EPSILON) {
            // Vertical or no movement
            if (motY > 0) return BlockFace.UP
            if (motY < 0) return BlockFace.DOWN
            return BlockFace.NORTH
        }

        if (useSubCardinal) {
            // 8-direction
            val angle = atan2(-motX, motZ)
            var deg = Math.toDegrees(angle)
            if (deg < 0) deg += FULL_CIRCLE_DEGREES

            if (deg < 22.5 || deg >= 337.5) return BlockFace.SOUTH
            if (deg < 67.5) return BlockFace.SOUTH_WEST
            if (deg < 112.5) return BlockFace.WEST
            if (deg < 157.5) return BlockFace.NORTH_WEST
            if (deg < 202.5) return BlockFace.NORTH
            if (deg < 247.5) return BlockFace.NORTH_EAST
            if (deg < 292.5) return BlockFace.EAST
            return BlockFace.SOUTH_EAST
        }

        // 4-direction
        return if (absX > absZ) {
            if (motX > 0) BlockFace.EAST else BlockFace.WEST
        } else {
            if (motZ > 0) BlockFace.SOUTH else BlockFace.NORTH
        }
    }

    @JvmStatic
    fun isSubCardinal(face: BlockFace): Boolean =
        face == BlockFace.NORTH_EAST ||
            face == BlockFace.NORTH_WEST ||
            face == BlockFace.SOUTH_EAST ||
            face == BlockFace.SOUTH_WEST
}
