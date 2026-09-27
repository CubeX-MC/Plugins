package org.cubexmc.spatial

import org.bukkit.Location

/**
 * An immutable 3D point used for spatial queries in the octree index.
 */
public class Point3D {
    @JvmField public val x: Double
    @JvmField public val y: Double
    @JvmField public val z: Double

    public constructor(x: Double, y: Double, z: Double) {
        this.x = x
        this.y = y
        this.z = z
    }

    public constructor(loc: Location) {
        x = loc.x
        y = loc.y
        z = loc.z
    }
}
