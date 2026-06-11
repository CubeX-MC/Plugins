package org.cubexmc.metro.spatial

import org.bukkit.Location

/**
 * An immutable 3D point used for spatial queries in the octree index.
 */
class Point3D {
    @JvmField val x: Double
    @JvmField val y: Double
    @JvmField val z: Double

    constructor(x: Double, y: Double, z: Double) {
        this.x = x
        this.y = y
        this.z = z
    }

    constructor(loc: Location) {
        x = loc.x
        y = loc.y
        z = loc.z
    }
}
