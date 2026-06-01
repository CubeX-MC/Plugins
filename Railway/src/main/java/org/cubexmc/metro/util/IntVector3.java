package org.cubexmc.metro.util;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;

/**
 * Represents a class containing three immutable integer coordinates: x, y and z
 * Simplified version from BKCommonLib for Metro plugin use
 */
public class IntVector3 implements Comparable<IntVector3> {

    public static final IntVector3 ZERO = new IntVector3(0, 0, 0);
    public final int x, y, z;

    public IntVector3(Block block) {
        this(block.getX(), block.getY(), block.getZ());
    }

    public IntVector3(Location loc) {
        this(loc.getX(), loc.getY(), loc.getZ());
    }

    public IntVector3(final double x, final double y, final double z) {
        this((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    public IntVector3(final int x, final int y, final int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public String toString() {
        return "{" + x + ", " + y + ", " + z + "}";
    }

    @Override
    public boolean equals(Object object) {
        if (object == this) {
            return true;
        } else if (object instanceof IntVector3) {
            IntVector3 other = (IntVector3) object;
            return this.x == other.x && this.y == other.y && this.z == other.z;
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        int hash = 1;
        hash = hash * 31 + this.x;
        hash = hash * 31 + this.y;
        hash = hash * 31 + this.z;
        return hash;
    }

    @Override
    public int compareTo(IntVector3 o) {
        if (this.x != o.x) {
            return Integer.compare(this.x, o.x);
        } else if (this.y != o.y) {
            return Integer.compare(this.y, o.y);
        } else {
            return Integer.compare(this.z, o.z);
        }
    }

    public boolean isSame(IntVector3 other) {
        return this.x == other.x && this.y == other.y && this.z == other.z;
    }

    public IntVector3 add(IntVector3 other) {
        return new IntVector3(this.x + other.x, this.y + other.y, this.z + other.z);
    }

    public IntVector3 subtract(IntVector3 other) {
        return new IntVector3(this.x - other.x, this.y - other.y, this.z - other.z);
    }

    public Block toBlock(World world) {
        return world.getBlockAt(this.x, this.y, this.z);
    }

    public Location toLocation(World world) {
        return new Location(world, this.x, this.y, this.z);
    }

    public Vector toVector() {
        return new Vector(this.x, this.y, this.z);
    }

    public double distance(IntVector3 other) {
        return Math.sqrt(distanceSquared(other));
    }

    public double distanceSquared(IntVector3 other) {
        double dx = this.x - other.x;
        double dy = this.y - other.y;
        double dz = this.z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
