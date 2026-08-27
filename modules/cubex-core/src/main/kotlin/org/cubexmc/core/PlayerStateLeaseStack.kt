package org.cubexmc.core

import java.io.StringReader
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Properties
import java.util.function.Consumer
import java.util.function.Supplier
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType

/**
 * A stateless, PDC-backed ownership stack for scalar player state controlled by embedded plugins.
 * The fixed `cubex` key keeps independently relocated copies on one shared stack.
 */
object PlayerStateLeaseStack {
    fun hasLeases(player: Player, channel: String): Boolean =
        access(player, channel)?.load()?.order?.isNotEmpty() == true

    fun apply(
        player: Player,
        channel: String,
        token: String,
        value: String,
        readCurrent: Supplier<String>,
        writeCurrent: Consumer<String>,
    ): Boolean {
        val access = access(player, channel) ?: return false
        val state = access.load()
        if (state.order.isEmpty()) state.base = readCurrent.get()
        state.order.remove(token)
        state.order += token
        state.values[token] = value
        access.save(state)
        writeCurrent.accept(value)
        return true
    }

    fun reapply(player: Player, channel: String, token: String, writeCurrent: Consumer<String>): Boolean {
        val access = access(player, channel) ?: return false
        val state = access.load()
        if (state.order.lastOrNull() != token) return token in state.order
        val value = state.values[token] ?: return false
        writeCurrent.accept(value)
        return true
    }

    fun remove(player: Player, channel: String, token: String, writeCurrent: Consumer<String>): Boolean {
        val access = access(player, channel) ?: return false
        val state = access.load()
        val wasTop = state.order.lastOrNull() == token
        if (!state.order.remove(token)) return false
        state.values.remove(token)
        val target = state.order.lastOrNull()?.let(state.values::get) ?: state.base
        access.save(state)
        if (wasTop && target != null) writeCurrent.accept(target)
        return true
    }

    private fun access(player: Player, channel: String): Access? {
        val container = player.persistentDataContainer ?: return null
        val normalized = channel.lowercase().replace(Regex("[^a-z0-9._-]"), "-")
        val key = NamespacedKey.fromString("cubex:player-state-$normalized") ?: return null
        return Access(container, key)
    }

    private class Access(
        private val container: org.bukkit.persistence.PersistentDataContainer,
        private val key: NamespacedKey,
    ) {
        fun load(): State {
            val raw = container.get(key, PersistentDataType.STRING) ?: return State()
            val properties = Properties().apply { load(StringReader(raw)) }
            val order = properties.getProperty("order", "")
                .split(',')
                .filter(String::isNotBlank)
                .mapNotNull(::decode)
                .toMutableList()
            val values = LinkedHashMap<String, String>()
            for (token in order) {
                properties.getProperty("value.${encode(token)}")?.let { values[token] = it }
            }
            order.retainAll(values.keys)
            return State(properties.getProperty("base"), order, values)
        }

        fun save(state: State) {
            if (state.order.isEmpty()) {
                container.remove(key)
                return
            }
            val properties = Properties()
            state.base?.let { properties.setProperty("base", it) }
            properties.setProperty("order", state.order.joinToString(",", transform = ::encode))
            for ((token, value) in state.values) properties.setProperty("value.${encode(token)}", value)
            val writer = StringWriter()
            properties.store(writer, null)
            container.set(key, PersistentDataType.STRING, writer.toString())
        }
    }

    private data class State(
        var base: String? = null,
        val order: MutableList<String> = mutableListOf(),
        val values: MutableMap<String, String> = LinkedHashMap(),
    )

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    }.getOrNull()
}
