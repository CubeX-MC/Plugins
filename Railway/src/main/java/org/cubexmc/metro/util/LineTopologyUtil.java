package org.cubexmc.metro.util;

import java.util.List;

public final class LineTopologyUtil {

    private LineTopologyUtil() {
    }

    public static boolean isLoop(List<String> stopIds) {
        if (stopIds == null || stopIds.size() < 2) {
            return false;
        }
        String first = stopIds.get(0);
        String last = stopIds.get(stopIds.size() - 1);
        return first != null && first.equals(last);
    }
}
