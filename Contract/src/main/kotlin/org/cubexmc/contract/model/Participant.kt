package org.cubexmc.contract.model

import java.math.BigDecimal
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Objects
import java.util.UUID

class Participant(
    private val role: ParticipantRole,
    private var uuid: UUID?,
    private var displayName: String?,
    stake: List<Asset>,
) {
    private val stake: MutableList<Asset> = ArrayList(stake)

    fun role(): ParticipantRole = role

    fun uuid(): UUID? = uuid

    fun uuid(uuid: UUID?) {
        this.uuid = uuid
    }

    fun displayName(): String? = displayName

    fun displayName(displayName: String?) {
        this.displayName = displayName
    }

    fun stake(): List<Asset> = Collections.unmodifiableList(ArrayList(stake))

    fun stake(stake: List<Asset>) {
        this.stake.clear()
        this.stake.addAll(stake)
    }

    fun addStake(asset: Asset) {
        stake.add(asset)
    }

    fun moneyStake(): BigDecimal =
        stake.filter { it.isMoney() }.map { it.amount() }.fold(BigDecimal.ZERO) { left, right -> left.add(right) }

    fun itemStakeCount(): Int = stake.count { it.kind() == AssetKind.ITEM }

    fun toMap(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        map["role"] = role.name
        map["uuid"] = uuid?.toString()
        map["name"] = displayName
        val stakeList = ArrayList<Map<String, Any?>>()
        for (asset in stake) {
            stakeList.add(asset.toMap())
        }
        map["stake"] = stakeList
        return map
    }

    companion object {
        @JvmStatic
        fun fromMap(map: Map<*, *>): Participant {
            val role = ParticipantRole.valueOf(Objects.toString(map["role"], "OWNER"))
            val uuidRaw = map["uuid"]
            val uuid = if (uuidRaw == null || uuidRaw.toString().isBlank()) null else UUID.fromString(uuidRaw.toString())
            val name = map["name"]?.toString()
            val assets = ArrayList<Asset>()
            val stakeRaw = map["stake"]
            if (stakeRaw is List<*>) {
                for (entry in stakeRaw) {
                    if (entry is Map<*, *>) {
                        assets.add(Asset.fromMap(entry))
                    }
                }
            }
            return Participant(role, uuid, name, assets)
        }
    }
}
