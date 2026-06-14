package org.cubexmc.reputations.service

import org.cubexmc.reputations.api.ReputationField
import org.cubexmc.reputations.api.ReputationProfile
import org.cubexmc.reputations.api.ReputationService
import org.cubexmc.reputations.storage.ReputationStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/** Default [ReputationService] backed by [ReputationStore]; registered with the ServicesManager. */
class ReputationServiceImpl(
    private val store: ReputationStore,
    private val logger: Logger,
) : ReputationService {

    private val fields: MutableMap<String, ReputationField> = ConcurrentHashMap()

    override fun registerField(field: ReputationField) {
        val replaced = fields.put(field.key(), field) != null
        logger.info("${if (replaced) "Re-registered" else "Registered"} reputation field ${field.key()} (${field.displayName()})")
    }

    override fun fields(): MutableCollection<ReputationField> = ArrayList(fields.values)

    override fun field(fieldKey: String): ReputationField? = fields[fieldKey]

    override fun get(playerId: UUID, fieldKey: String): Double = store.get(playerId, fieldKey, defaultOf(fieldKey))

    override fun set(playerId: UUID, fieldKey: String, value: Double) {
        store.set(playerId, fieldKey, value)
    }

    override fun add(playerId: UUID, fieldKey: String, delta: Double): Double =
        store.add(playerId, fieldKey, delta, defaultOf(fieldKey))

    override fun reset(playerId: UUID, fieldKey: String) {
        store.reset(playerId, fieldKey)
    }

    override fun profile(playerId: UUID): ReputationProfile = SimpleProfile(playerId, store.valuesOf(playerId), this)

    private fun defaultOf(fieldKey: String): Double = fields[fieldKey]?.defaultValue() ?: 0.0

    private class SimpleProfile(
        private val playerId: UUID,
        private val values: Map<String, Double>,
        private val service: ReputationService,
    ) : ReputationProfile {
        override fun playerId(): UUID = playerId
        override fun value(fieldKey: String): Double = values[fieldKey] ?: (service.field(fieldKey)?.defaultValue() ?: 0.0)
        override fun values(): MutableMap<String, Double> = HashMap(values)
    }
}
