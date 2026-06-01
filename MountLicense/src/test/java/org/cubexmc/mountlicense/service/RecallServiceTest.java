package org.cubexmc.mountlicense.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

class RecallServiceTest {

    @Test
    void safeDestinationRequiresPassableFeetAndHeadOverSolidGround() {
        assertTrue(destination(Material.AIR, true, false,
                Material.AIR, true, false,
                Material.STONE, false, false));

        assertFalse(destination(Material.STONE, false, false,
                Material.AIR, true, false,
                Material.STONE, false, false));

        assertFalse(destination(Material.AIR, true, false,
                Material.STONE, false, false,
                Material.STONE, false, false));
    }

    @Test
    void safeDestinationRejectsLiquidAndHazardousBlocks() {
        assertFalse(destination(Material.WATER, true, true,
                Material.AIR, true, false,
                Material.STONE, false, false));

        assertFalse(destination(Material.AIR, true, false,
                Material.AIR, true, false,
                Material.MAGMA_BLOCK, false, false));

        assertFalse(destination(Material.FIRE, true, false,
                Material.AIR, true, false,
                Material.STONE, false, false));
    }

    private static boolean destination(Material feetType, boolean feetPassable, boolean feetLiquid,
                                       Material headType, boolean headPassable, boolean headLiquid,
                                       Material groundType, boolean groundPassable, boolean groundLiquid) {
        Location location = mock(Location.class);
        World world = mock(World.class);
        Block feet = block(feetType, feetPassable, feetLiquid);
        Block head = block(headType, headPassable, headLiquid);
        Block ground = block(groundType, groundPassable, groundLiquid);

        when(location.getWorld()).thenReturn(world);
        when(location.getBlock()).thenReturn(feet);
        when(feet.getRelative(0, 1, 0)).thenReturn(head);
        when(feet.getRelative(0, -1, 0)).thenReturn(ground);

        return RecallService.isSafeDestination(location);
    }

    private static Block block(Material type, boolean passable, boolean liquid) {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(type);
        when(block.isPassable()).thenReturn(passable);
        when(block.isLiquid()).thenReturn(liquid);
        return block;
    }
}
