package org.cubexmc.contract.model

import java.util.LinkedHashMap
import java.util.Objects

class PayoutRecipient private constructor(
    private val kind: Kind,
    private val role: ParticipantRole?,
) {
    enum class Kind {
        PARTICIPANT,
        SYSTEM_SINK,
        ARBITER,
    }

    fun kind(): Kind = kind

    fun role(): ParticipantRole? = role

    fun toMap(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        map["kind"] = kind.name
        if (role != null) {
            map["role"] = role.name
        }
        return map
    }

    companion object {
        @JvmStatic
        fun participant(role: ParticipantRole): PayoutRecipient = PayoutRecipient(Kind.PARTICIPANT, role)

        @JvmStatic
        fun systemSink(): PayoutRecipient = PayoutRecipient(Kind.SYSTEM_SINK, null)

        @JvmStatic
        fun arbiter(): PayoutRecipient = PayoutRecipient(Kind.ARBITER, null)

        @JvmStatic
        fun fromMap(map: Map<*, *>): PayoutRecipient {
            val kind = Kind.valueOf(Objects.toString(map["kind"], "SYSTEM_SINK"))
            if (kind == Kind.PARTICIPANT) {
                return participant(ParticipantRole.valueOf(Objects.toString(map["role"], "OWNER")))
            }
            if (kind == Kind.ARBITER) {
                return arbiter()
            }
            return systemSink()
        }
    }
}
