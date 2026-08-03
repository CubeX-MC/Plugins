package org.cubexmc.metro.util

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.util.Vector
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Represents a class containing three immutable integer coordinates: x, y and z
 * Simplified version from BKCommonLib for Metro plugin use
 */
class IntVector3(@JvmField val x: Int, @JvmField val y: Int, @JvmField val z: Int) : Comparable<IntVector3> {

    constructor(block: Block) : this(block.x, block.y, block.z)

    constructor(loc: Location) : this(loc.x, loc.y, loc.z)

    constructor(x: Double, y: Double, z: Double) : this(
        floor(x).toInt(),
        floor(y).toInt(),
        floor(z).toInt(),
    )

    override fun toString(): String = "{$x, $y, $z}"

    override fun equals(other: Any?): Boolean {
        if (other === this) {
            return true
        }
        return other is IntVector3 && x == other.x && y == other.y && z == other.z
    }

    override fun hashCode(): Int {
        var hash = 1
        hash = hash * HASH_MULTIPLIER + x
        hash = hash * HASH_MULTIPLIER + y
        hash = hash * HASH_MULTIPLIER + z
        return hash
    }

    override fun compareTo(other: IntVector3): Int =
        when {
            x != other.x -> x.compareTo(other.x)
            y != other.y -> y.compareTo(other.y)
            else -> z.compareTo(other.z)
        }

    fun isSame(other: IntVector3): Boolean = x == other.x && y == other.y && z == other.z

    fun add(other: IntVector3): IntVector3 = IntVector3(x + other.x, y + other.y, z + other.z)

    fun subtract(other: IntVector3): IntVector3 = IntVector3(x - other.x, y - other.y, z - other.z)

    fun toBlock(world: World): Block = world.getBlockAt(x, y, z)

    fun toLocation(world: World): Location = Location(world, x.toDouble(), y.toDouble(), z.toDouble())

    fun toVector(): Vector = Vector(x, y, z)

    fun distance(other: IntVector3): Double = sqrt(distanceSquared(other))

    fun distanceSquared(other: IntVector3): Double {
        val dx = (x - other.x).toDouble()
        val dy = (y - other.y).toDouble()
        val dz = (z - other.z).toDouble()
        return dx * dx + dy * dy + dz * dz
    }

    companion object {
        private const val HASH_MULTIPLIER = 31

        @JvmField
        val ZERO = IntVector3(0, 0, 0)
    }
}
