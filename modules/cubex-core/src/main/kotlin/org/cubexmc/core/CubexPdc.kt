package org.cubexmc.core

import java.util.UUID
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType

/**
 * `PersistentDataContainer` 的读写样板。
 *
 * 全仓实测：`STRING` 42 处、`BYTE`（当布尔标记用）26 处、`INTEGER` 7 处、`LONG` 2 处，
 * 分布在 BookLite / MountLicense / RuleGems / Clarity / Metro / Railway / Contract。
 *
 * 下沉的重点**不是**少打几个字，而是把"外部数据不可信"这件事收敛到一处：
 * PDC 里的内容可以被手改、被别的插件写坏、被版本迁移留下半截。
 * 现状是 MountLicense 为此写了**四处一模一样**的 `try/catch UUID.fromString`，
 * 而枚举名的解析各处则完全没有防护。这里统一成"读坏了返回 null"，调用方不必再自己包。
 *
 * 纯解码逻辑放在 [CubexPdc]，扩展函数只是薄薄一层 —— 这样解码能被单测直接覆盖，不需要 mock 服务器。
 */
object CubexPdc {

    /** 标记位写入的值。读取一律按"键是否存在"判断，与下沉前各插件的 `has(key, BYTE)` 语义一致。 */
    const val FLAG_VALUE: Byte = 1

    /** 解析 UUID；空值或格式不合法都返回 null，绝不抛。 */
    fun decodeUuid(raw: String?): UUID? {
        if (raw.isNullOrEmpty()) return null
        return try {
            UUID.fromString(raw)
        } catch (ex: IllegalArgumentException) {
            null
        }
    }

    /**
     * 按**名字**解析枚举；未知名字返回 null。
     *
     * 用 `valueOf` 会抛 `IllegalArgumentException` —— 枚举增删项是很常见的版本演进，
     * 旧存档里留着已删除的名字不该让插件炸掉。
     */
    fun <E : Enum<E>> decodeEnum(raw: String?, values: Array<E>): E? {
        if (raw.isNullOrEmpty()) return null
        return values.firstOrNull { it.name == raw }
    }
}

// —— 布尔标记（底层是 BYTE）——

/** 是否带有该标记。按键是否存在判断，忽略值——与下沉前各处的 `has(key, BYTE)` 完全一致。 */
fun PersistentDataContainer.hasFlag(key: NamespacedKey): Boolean = has(key, PersistentDataType.BYTE)

fun PersistentDataContainer.setFlag(key: NamespacedKey) {
    set(key, PersistentDataType.BYTE, CubexPdc.FLAG_VALUE)
}

fun PersistentDataContainer.clearFlag(key: NamespacedKey) {
    remove(key)
}

// —— UUID（底层是 STRING）——

/** 读 UUID；缺失或格式损坏都返回 null。 */
fun PersistentDataContainer.getUuid(key: NamespacedKey): UUID? =
    CubexPdc.decodeUuid(get(key, PersistentDataType.STRING))

fun PersistentDataContainer.setUuid(key: NamespacedKey, value: UUID) {
    set(key, PersistentDataType.STRING, value.toString())
}

// —— 枚举（底层是 STRING，存 name）——

/** 读枚举；缺失或名字已不存在都返回 null。 */
inline fun <reified E : Enum<E>> PersistentDataContainer.getEnum(key: NamespacedKey): E? =
    CubexPdc.decodeEnum(get(key, PersistentDataType.STRING), enumValues<E>())

fun <E : Enum<E>> PersistentDataContainer.setEnum(key: NamespacedKey, value: E) {
    set(key, PersistentDataType.STRING, value.name)
}

// —— 基础类型的带默认值读取 ——

fun PersistentDataContainer.getStringOr(key: NamespacedKey, fallback: String): String =
    get(key, PersistentDataType.STRING) ?: fallback

fun PersistentDataContainer.getIntOr(key: NamespacedKey, fallback: Int): Int =
    get(key, PersistentDataType.INTEGER) ?: fallback

fun PersistentDataContainer.getLongOr(key: NamespacedKey, fallback: Long): Long =
    get(key, PersistentDataType.LONG) ?: fallback
