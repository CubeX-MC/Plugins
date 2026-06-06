package org.cubexmc.contract.model

import java.math.BigDecimal
import java.util.LinkedHashMap
import java.util.Objects

class Asset private constructor(
    private val kind: AssetKind,
    private val amount: BigDecimal,
    private val reference: String?,
) {
    fun kind(): AssetKind = kind

    fun amount(): BigDecimal = amount

    fun reference(): String? = reference

    fun isMoney(): Boolean = kind == AssetKind.MONEY

    fun toMap(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        map["kind"] = kind.name
        if (kind == AssetKind.MONEY) {
            map["amount"] = amount.toPlainString()
        } else {
            map["reference"] = reference
        }
        return map
    }

    companion object {
        @JvmStatic
        fun money(amount: BigDecimal): Asset = Asset(AssetKind.MONEY, amount, null)

        @JvmStatic
        fun item(reference: String): Asset = Asset(AssetKind.ITEM, BigDecimal.ZERO, reference)

        @JvmStatic
        fun landPermission(reference: String): Asset = Asset(AssetKind.LAND_PERMISSION, BigDecimal.ZERO, reference)

        @JvmStatic
        fun fromMap(map: Map<*, *>): Asset {
            val kind = AssetKind.valueOf(Objects.toString(map["kind"], "MONEY"))
            if (kind == AssetKind.MONEY) {
                val raw = map["amount"]
                val value = if (raw == null) BigDecimal.ZERO else BigDecimal(raw.toString())
                return money(value)
            }
            val ref = Objects.toString(map["reference"], "")
            return Asset(kind, BigDecimal.ZERO, ref)
        }
    }
}
