package org.cubexmc.metro.util;

import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;

/**
 * Utility functions from TrainCarts
 */
public class FaceUtil {
    
    public static BlockFace vecToFace(double motX, double motY, double motZ, boolean useSubCardinal) {
        // Simplify to horizontal direction for minecarts
        double absX = Math.abs(motX);
        double absZ = Math.abs(motZ);
        
        if (absX < 1e-6 && absZ < 1e-6) {
            // Vertical or no movement
            if (motY > 0) return BlockFace.UP;
            if (motY < 0) return BlockFace.DOWN;
            return BlockFace.NORTH;
        }
        
        if (useSubCardinal) {
            // 8-direction
            double angle = Math.atan2(-motX, motZ);
            double deg = Math.toDegrees(angle);
            if (deg < 0) deg += 360;
            
            if (deg < 22.5 || deg >= 337.5) return BlockFace.SOUTH;
            if (deg < 67.5) return BlockFace.SOUTH_WEST;
            if (deg < 112.5) return BlockFace.WEST;
            if (deg < 157.5) return BlockFace.NORTH_WEST;
            if (deg < 202.5) return BlockFace.NORTH;
            if (deg < 247.5) return BlockFace.NORTH_EAST;
            if (deg < 292.5) return BlockFace.EAST;
            return BlockFace.SOUTH_EAST;
        } else {
            // 4-direction
            if (absX > absZ) {
                return motX > 0 ? BlockFace.EAST : BlockFace.WEST;
            } else {
                return motZ > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
            }
        }
    }
    
    public static boolean isSubCardinal(BlockFace face) {
        return face == BlockFace.NORTH_EAST || 
               face == BlockFace.NORTH_WEST || 
               face == BlockFace.SOUTH_EAST || 
               face == BlockFace.SOUTH_WEST;
    }
}
