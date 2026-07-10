package org.cubexmc.contract.model

import org.bukkit.Material
import java.util.LinkedHashMap
import java.util.Locale
import java.util.Objects
import kotlin.math.max
import kotlin.math.min

class ContractObjective(
    private val type: ObjectiveType,
    private val target: String,
    required: Int,
    progress: Int,
) {
    private val required: Int = max(1, required)
    private var progress: Int = min(max(0, progress), this.required)

    fun type(): ObjectiveType = type

    fun target(): String = target

    fun required(): Int = required

    fun progress(): Int = progress

    fun progress(progress: Int) {
        this.progress = min(max(0, progress), required)
    }

    fun remaining(): Int = max(0, required - progress)

    fun complete(): Boolean = progress >= required

    fun addProgress(amount: Int): Int {
        if (amount <= 0 || complete()) {
            return 0
        }
        val before = progress
        progress = min(required, progress + amount)
        return progress - before
    }

    fun matches(signalTarget: String): Boolean {
        val wanted = normalize(target)
        if (wanted == ANY) {
            return true
        }
        val signal = normalize(signalTarget)
        return when (type) {
            ObjectiveType.RUN_COMMAND -> signal == wanted || signal.startsWith("$wanted ")
            else -> wanted == signal
        }
    }

    fun progressText(): String = "$progress/$required"

    fun toMap(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        map["type"] = type.name
        map["target"] = target
        map["required"] = required
        map["progress"] = progress
        return map
    }

    companion object {
        private const val ANY = "ANY"

        @JvmStatic
        fun of(type: ObjectiveType, target: String?, required: Int): ContractObjective =
            ContractObjective(type, cleanTarget(type, target), required, 0)

        @JvmStatic
        fun fromMap(map: Map<*, *>): ContractObjective {
            val type = ObjectiveType.valueOf(Objects.toString(map["type"], ObjectiveType.KILL_ENTITY.name))
            val target = Objects.toString(map["target"], ANY)
            val required = intValue(map["required"], 1)
            val progress = intValue(map["progress"], 0)
            return ContractObjective(type, target, required, progress)
        }

        @JvmStatic
        fun cleanTarget(type: ObjectiveType, target: String?): String {
            val trimmed = target?.trim()?.removePrefix("/") ?: ""
            if (trimmed.isBlank()) {
                return if (type.allowsAnyTarget()) ANY else ""
            }
            if (trimmed.equals(ANY, ignoreCase = true)) {
                return ANY
            }
            return when {
                type == ObjectiveType.RUN_COMMAND -> trimmed.lowercase(Locale.ROOT)
                type.materialTarget() -> Material.matchMaterial(trimmed)?.name ?: trimmed.uppercase(Locale.ROOT)
                else -> trimmed.uppercase(Locale.ROOT)
            }
        }

        private fun normalize(value: String): String =
            value.trim().removePrefix("/").uppercase(Locale.ROOT)

        private fun ObjectiveType.allowsAnyTarget(): Boolean =
            when (this) {
                ObjectiveType.FISH,
                ObjectiveType.KILL_PLAYER,
                ObjectiveType.SHEAR,
                ObjectiveType.BREED,
                ObjectiveType.TAME,
                ObjectiveType.CHAT,
                ObjectiveType.BLOCK_INTERACT,
                ObjectiveType.USE_ITEM,
                ObjectiveType.DELIVER_MONEY,
                -> true
                else -> false
            }

        private fun ObjectiveType.materialTarget(): Boolean =
            when (this) {
                ObjectiveType.CRAFT_ITEM,
                ObjectiveType.BLOCK_BREAK,
                ObjectiveType.FISH,
                ObjectiveType.BLOCK_PLACE,
                ObjectiveType.CONSUME_ITEM,
                ObjectiveType.DELIVER_ITEM,
                ObjectiveType.ENCHANT_ITEM,
                ObjectiveType.BLOCK_INTERACT,
                ObjectiveType.USE_ITEM,
                -> true
                else -> false
            }

        private fun intValue(value: Any?, fallback: Int): Int =
            when (value) {
                is Number -> value.toInt()
                null -> fallback
                else -> value.toString().toIntOrNull() ?: fallback
            }
    }
}
