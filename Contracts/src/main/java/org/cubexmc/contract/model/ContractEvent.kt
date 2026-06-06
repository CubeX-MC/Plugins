package org.cubexmc.contract.model

import java.util.LinkedHashMap

class ContractEvent(
    private val time: Long,
    private val type: String,
    private val detail: String,
) {
    fun time(): Long = time

    fun type(): String = type

    fun detail(): String = detail

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContractEvent) return false
        return time == other.time && type == other.type && detail == other.detail
    }

    override fun hashCode(): Int {
        var result = time.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + detail.hashCode()
        return result
    }

    override fun toString(): String = "ContractEvent[time=$time, type=$type, detail=$detail]"

    fun toMap(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        map["time"] = time
        map["type"] = type
        map["detail"] = detail
        return map
    }

    companion object {
        @JvmStatic
        fun fromMap(map: Map<*, *>): ContractEvent {
            val time = if (map["time"] is Number) (map["time"] as Number).toLong() else 0L
            val typeRaw = map["type"]
            val detailRaw = map["detail"]
            val type = typeRaw?.toString() ?: ""
            val detail = detailRaw?.toString() ?: ""
            return ContractEvent(time, type, detail)
        }

        @JvmStatic
        fun fromLegacyLine(line: String): ContractEvent {
            val parts = line.split("\\|".toRegex(), limit = 3).toTypedArray()
            var time = 0L
            try {
                time = parts[0].toLong()
            } catch (ignored: NumberFormatException) {
            }
            val type = if (parts.size > 1) parts[1] else ""
            val detail = if (parts.size > 2) parts[2] else ""
            return ContractEvent(time, type, detail)
        }
    }
}
