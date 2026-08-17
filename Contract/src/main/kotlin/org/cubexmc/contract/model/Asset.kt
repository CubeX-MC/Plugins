package org.cubexmc.contract.model

import java.math.BigDecimal
import java.util.LinkedHashMap
import java.util.Objects
import org.bukkit.inventory.ItemStack

/**
 * One item in a participant's stake.
 *
 * MONEY carries a [BigDecimal] amount. ITEM carries the real [ItemStack] so the stake listing
 * cannot drift from the escrow actually held in [Contract.deliveryItems]/[Contract.rewardItems];
 * older saves that only stored a display string still load, just without the stack.
 */
class Asset private constructor(
    private val kind: AssetKind,
    private val amount: BigDecimal,
    private val reference: String?,
    private val item: ItemStack?,
) {
    fun kind(): AssetKind = kind

    fun amount(): BigDecimal = amount

    fun reference(): String? = reference

    fun isMoney(): Boolean = kind == AssetKind.MONEY

    /** The escrowed stack for an ITEM asset, or null for money and for legacy display-only records. */
    fun itemStack(): ItemStack? = item?.clone()

    /** How many physical items this asset represents; 0 for money. */
    fun itemCount(): Int = item?.amount ?: 0

    fun toMap(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        map["kind"] = kind.name
        if (kind == AssetKind.MONEY) {
            map["amount"] = amount.toPlainString()
            return map
        }
        map["reference"] = reference
        // Written alongside `reference` so a downgrade still renders something readable.
        item?.let { map["item"] = it.serialize() }
        return map
    }

    companion object {
        @JvmStatic
        fun money(amount: BigDecimal): Asset = Asset(AssetKind.MONEY, amount, null, null)

        /** A display-only item reference. Prefer [item] with a real stack where one exists. */
        @JvmStatic
        fun item(reference: String): Asset = Asset(AssetKind.ITEM, BigDecimal.ZERO, reference, null)

        @JvmStatic
        fun item(stack: ItemStack): Asset {
            val copy = stack.clone()
            return Asset(AssetKind.ITEM, BigDecimal.ZERO, describe(copy), copy)
        }

        @JvmStatic
        fun landPermission(reference: String): Asset =
            Asset(AssetKind.LAND_PERMISSION, BigDecimal.ZERO, reference, null)

        @JvmStatic
        fun describe(stack: ItemStack): String = "${stack.type.name} x ${stack.amount}"

        @JvmStatic
        fun fromMap(map: Map<*, *>): Asset {
            val kind = AssetKind.valueOf(Objects.toString(map["kind"], "MONEY"))
            if (kind == AssetKind.MONEY) {
                val raw = map["amount"]
                val value = if (raw == null) BigDecimal.ZERO else BigDecimal(raw.toString())
                return money(value)
            }
            val reference = Objects.toString(map["reference"], "")
            val stack = readItem(map["item"])
            if (kind == AssetKind.ITEM && stack != null) {
                // Keep the stored reference: it is what operators saw when the contract was written.
                return Asset(kind, BigDecimal.ZERO, reference.ifBlank { describe(stack) }, stack)
            }
            return Asset(kind, BigDecimal.ZERO, reference, null)
        }

        private fun readItem(raw: Any?): ItemStack? = when (raw) {
            is ItemStack -> raw.clone()
            is Map<*, *> -> runCatching {
                @Suppress("UNCHECKED_CAST")
                ItemStack.deserialize(raw as Map<String, Any>)
            }.getOrNull()
            else -> null
        }
    }
}
