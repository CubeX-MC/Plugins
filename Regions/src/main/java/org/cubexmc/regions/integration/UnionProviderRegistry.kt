package org.cubexmc.regions.integration

class UnionProviderRegistry {
    private val providers: MutableMap<String, UnionProvider> = LinkedHashMap()
    private var preferred: String = "lands"

    fun register(provider: UnionProvider) {
        providers[provider.type.lowercase()] = provider
    }

    fun setPreferred(type: String) {
        preferred = type.lowercase()
    }

    fun active(): UnionProvider? =
        providers[preferred]?.takeIf { it.isAvailable() }
            ?: providers.values.firstOrNull { it.isAvailable() }

    fun all(): Collection<UnionProvider> = providers.values.toList()
}

class FallbackUnionProvider : UnionProvider {
    override val type: String = "fallback"

    override fun isAvailable(): Boolean = true

    override fun getUnion(playerId: java.util.UUID) = null

    override fun areSameUnion(a: java.util.UUID, b: java.util.UUID): Boolean = false

    override fun areAllied(a: java.util.UUID, b: java.util.UUID): Boolean = false

    override fun areEnemies(a: java.util.UUID, b: java.util.UUID): Boolean = false

    override fun placeholder(playerId: java.util.UUID, key: String): String? = null
}
