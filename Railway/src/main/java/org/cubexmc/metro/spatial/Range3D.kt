package org.cubexmc.metro.spatial

/**
 * An immutable, axis-aligned 3D bounding box (AABB).
 */
class Range3D(
    x1: Double,
    y1: Double,
    z1: Double,
    x2: Double,
    y2: Double,
    z2: Double,
) {
    @JvmField val minX: Double = kotlin.math.min(x1, x2)
    @JvmField val minY: Double = kotlin.math.min(y1, y2)
    @JvmField val minZ: Double = kotlin.math.min(z1, z2)
    @JvmField val maxX: Double = kotlin.math.max(x1, x2)
    @JvmField val maxY: Double = kotlin.math.max(y1, y2)
    @JvmField val maxZ: Double = kotlin.math.max(z1, z2)

    fun contains(p: Point3D): Boolean =
        p.x >= minX && p.x < maxX && p.y >= minY && p.y < maxY && p.z >= minZ && p.z < maxZ

    fun intersects(o: Range3D): Boolean =
        (minX <= o.maxX && maxX >= o.minX) &&
            (minY <= o.maxY && maxY >= o.minY) &&
            (minZ <= o.maxZ && maxZ >= o.minZ)

    fun subdivide(): Array<Range3D> {
        val mx = (minX + maxX) / 2
        val my = (minY + maxY) / 2
        val mz = (minZ + maxZ) / 2
        return arrayOf(
            Range3D(minX, minY, minZ, mx, my, mz),
            Range3D(mx, minY, minZ, maxX, my, mz),
            Range3D(minX, my, minZ, mx, maxY, mz),
            Range3D(mx, my, minZ, maxX, maxY, mz),
            Range3D(minX, minY, mz, mx, my, maxZ),
            Range3D(mx, minY, mz, maxX, my, maxZ),
            Range3D(minX, my, mz, mx, maxY, maxZ),
            Range3D(mx, my, mz, maxX, maxY, maxZ),
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Range3D) return false
        return other.minX.compareTo(minX) == 0 &&
            other.minY.compareTo(minY) == 0 &&
            other.minZ.compareTo(minZ) == 0 &&
            other.maxX.compareTo(maxX) == 0 &&
            other.maxY.compareTo(maxY) == 0 &&
            other.maxZ.compareTo(maxZ) == 0
    }

    override fun hashCode(): Int {
        var result = 1
        var temp: Long
        temp = minX.toBits(); result = 31 * result + (temp xor (temp ushr 32)).toInt()
        temp = minY.toBits(); result = 31 * result + (temp xor (temp ushr 32)).toInt()
        temp = minZ.toBits(); result = 31 * result + (temp xor (temp ushr 32)).toInt()
        temp = maxX.toBits(); result = 31 * result + (temp xor (temp ushr 32)).toInt()
        temp = maxY.toBits(); result = 31 * result + (temp xor (temp ushr 32)).toInt()
        temp = maxZ.toBits(); result = 31 * result + (temp xor (temp ushr 32)).toInt()
        return result
    }
}
