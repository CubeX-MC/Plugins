package org.cubexmc.contract.model

import java.util.Collections
import java.util.UUID

/**
 * Immutable signature snapshot. An acceptance records a funded signature, not merely an invitation.
 * The service must persist it only after escrow succeeds, and owns the corresponding ContractStatus
 * change. Computing a new snapshot never mutates a contract or performs a Vault operation.
 */
class AllianceAgreement private constructor(
    members: List<UUID>,
    private val creatorUuid: UUID,
    signatures: Map<UUID, Long>,
    approvals: Set<UUID>,
) {
    private val members = Collections.unmodifiableList(ArrayList(members))
    private val signatures = Collections.unmodifiableMap(LinkedHashMap(signatures))
    private val approvals = Collections.unmodifiableSet(LinkedHashSet(approvals))

    init {
        require(members.size >= 3 && members.toSet().size == members.size) { "Alliance members must be unique and number at least three" }
        require(creatorUuid in members) { "Alliance creator must be a member" }
        val createdAt = requireNotNull(signatures[creatorUuid]) { "Alliance creator signature is missing" }
        require(createdAt >= 0) { "Alliance signature time must be non-negative" }
        require(signatures.keys.all { it in members } && signatures.values.all { it >= createdAt }) {
            "Alliance signatures must belong to members and cannot predate creation"
        }
        require(approvals.all { it in members } && (approvals.isEmpty() || signatures.size == members.size)) {
            "Alliance approval requires every member to have signed"
        }
    }

    fun members(): List<UUID> = members

    fun creatorUuid(): UUID = creatorUuid

    fun signatures(): Map<UUID, Long> = signatures

    fun approvals(): Set<UUID> = approvals

    fun hasAccepted(member: UUID): Boolean = signatures.containsKey(member)

    fun allAccepted(): Boolean = signatures.size == members.size

    fun allApproved(): Boolean = allAccepted() && approvals.size == members.size

    fun accept(member: UUID, acceptedAt: Long): AllianceAgreement {
        require(member in members) { "Only invited alliance members may sign" }
        if (hasAccepted(member)) return this
        return AllianceAgreement(members, creatorUuid, signatures + (member to acceptedAt), approvals)
    }

    fun approve(member: UUID): AllianceAgreement {
        require(member in members && allAccepted()) { "Only fully signed alliance members may approve" }
        if (member in approvals) return this
        return AllianceAgreement(members, creatorUuid, signatures, approvals + member)
    }

    fun toMap(): Map<String, Any> = linkedMapOf(
        "version" to 1,
        "signatures" to members.filter { hasAccepted(it) }.map {
            linkedMapOf("uuid" to it.toString(), "accepted-at" to signatures.getValue(it))
        },
        "approvals" to members.filter { it in approvals }.map { it.toString() },
    )

    companion object {
        @JvmStatic
        fun create(members: List<UUID>, creatorUuid: UUID, createdAt: Long): AllianceAgreement =
            AllianceAgreement(members, creatorUuid, mapOf(creatorUuid to createdAt), emptySet())

        @JvmStatic
        fun fromMap(members: List<UUID>, creatorUuid: UUID, map: Map<*, *>): AllianceAgreement {
            require(map["version"].toString() == "1") { "Unsupported alliance signature format" }
            val entries = map["signatures"] as? List<*>
                ?: throw IllegalArgumentException("Alliance signatures are missing")
            val signatures = LinkedHashMap<UUID, Long>()
            for (raw in entries) {
                val entry = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid alliance signature")
                val member = UUID.fromString(entry["uuid"].toString())
                val acceptedAt = entry["accepted-at"]?.toString()?.toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid alliance signature time")
                require(signatures.putIfAbsent(member, acceptedAt) == null) { "Duplicate alliance signature" }
            }
            val approvalEntries = map["approvals"] as? List<*>
                ?: throw IllegalArgumentException("Alliance approvals are missing")
            val approvals = LinkedHashSet<UUID>()
            for (raw in approvalEntries) {
                require(approvals.add(UUID.fromString(raw.toString()))) { "Duplicate alliance approval" }
            }
            return AllianceAgreement(members, creatorUuid, signatures, approvals)
        }
    }
}
