package org.cubexmc.metro.util

object LineTopologyUtil {

    @JvmStatic
    fun isLoop(stopIds: List<String?>?): Boolean {
        if (stopIds == null || stopIds.size < 2) {
            return false
        }
        val first = stopIds[0]
        val last = stopIds[stopIds.size - 1]
        return first != null && first == last
    }
}
