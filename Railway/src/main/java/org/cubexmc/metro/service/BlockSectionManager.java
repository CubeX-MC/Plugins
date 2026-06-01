package org.cubexmc.metro.service;

import java.util.HashSet;
import java.util.Set;

/**
 * Simple skeleton for section occupancy. Real implementation will 
 * map (lineId, fromStopId, toStopId, direction) -> occupiedByTrainId
 */
public class BlockSectionManager {

    private final Set<String> occupiedKeys = new HashSet<>();

    public boolean tryEnter(String sectionKey) {
        return occupiedKeys.add(sectionKey);
    }

    public void leave(String sectionKey) {
        occupiedKeys.remove(sectionKey);
    }

    public boolean isOccupied(String sectionKey) {
        return occupiedKeys.contains(sectionKey);
    }
}


