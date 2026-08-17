package org.cubexmc.contract.integration.reputation

import org.cubexmc.core.CubexLogger
import org.cubexmc.integrations.OptionalServiceConnection
import org.cubexmc.integrations.OptionalServiceConnector
import org.cubexmc.integrations.OptionalServiceDescriptor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.UUID

/**
 * Best-effort mirror of Contract reputation deltas into the optional Reputations plugin.
 *
 * Provider types are resolved reflectively through Reputations' class loader. Contract therefore
 * keeps working, and keeps its own `reputation.yml`, when Reputations is absent or incompatible.
 */
class ReputationsMirror(
    private val connector: OptionalServiceConnector,
    private val logger: CubexLogger,
) : ReputationDeltaSink {
    @Volatile
    private var current: Binding? = null
    private var lastFailure: String? = null

    fun available(): Boolean = safeBinding() != null

    override fun add(playerId: UUID, fieldId: String, delta: Double) {
        val binding = safeBinding() ?: return
        try {
            binding.add.invoke(binding.service, playerId, "$NAMESPACE:$fieldId", delta)
        } catch (ex: ReflectiveOperationException) {
            invalidate(binding, "Reputations rejected a Contract reputation update", rootCause(ex))
        } catch (ex: LinkageError) {
            invalidate(binding, "Reputations became binary-incompatible during a Contract reputation update", ex)
        }
    }

    private fun safeBinding(): Binding? {
        return try {
            resolveBinding()
        } catch (ex: Exception) {
            incompatible("Reputations service discovery failed", ex)
            null
        } catch (ex: LinkageError) {
            incompatible("Reputations service discovery became binary-incompatible", ex)
            null
        }
    }

    @Synchronized
    private fun resolveBinding(): Binding? {
        return when (val connection = connector.connect(DESCRIPTOR)) {
            is OptionalServiceConnection.Unavailable -> {
                current = null
                null
            }
            is OptionalServiceConnection.Connected -> {
                current?.takeIf {
                    it.service === connection.service && it.apiType === connection.apiType
                } ?: bind(connection)
            }
        }
    }

    private fun bind(connection: OptionalServiceConnection.Connected): Binding? {
        return try {
            val loader = connection.provider.javaClass.classLoader
            val fieldType = Class.forName(FIELD_CLASS_NAME, false, loader)
            val registerField = connection.apiType.getMethod("registerField", fieldType)
            val add = connection.apiType.getMethod("add", UUID::class.java, String::class.java, Double::class.javaPrimitiveType)

            for (field in FIELDS) {
                registerField.invoke(connection.service, buildField(fieldType, field))
            }

            Binding(connection.service, connection.apiType, add).also {
                current = it
                lastFailure = null
            }
        } catch (ex: ReflectiveOperationException) {
            incompatible("Reputations API is incompatible with the Contract bridge", rootCause(ex))
            null
        } catch (ex: LinkageError) {
            incompatible("Reputations API could not be linked by the Contract bridge", ex)
            null
        }
    }

    private fun buildField(fieldType: Class<*>, spec: FieldSpec): Any {
        var builder = fieldType.getMethod("builder", String::class.java, String::class.java)
            .invoke(null, NAMESPACE, spec.id)
        builder = builder.javaClass.getMethod("displayName", String::class.java).invoke(builder, spec.displayName)
        builder = builder.javaClass.getMethod("description", String::class.java).invoke(builder, spec.description)
        builder = builder.javaClass.getMethod("icon", String::class.java).invoke(builder, spec.icon)
        builder = builder.javaClass.getMethod("higherIsBetter", Boolean::class.javaPrimitiveType)
            .invoke(builder, spec.higherIsBetter)
        return builder.javaClass.getMethod("build").invoke(builder)
    }

    @Synchronized
    private fun invalidate(binding: Binding, message: String, failure: Throwable) {
        if (current === binding) {
            current = null
        }
        incompatible(message, failure)
    }

    private fun incompatible(message: String, failure: Throwable) {
        val signature = "$message: ${failure.javaClass.name}: ${failure.message.orEmpty()}"
        if (lastFailure != signature) {
            lastFailure = signature
            logger.warn("$message; Contract will continue with local reputation only.", failure)
        }
    }

    private fun rootCause(exception: ReflectiveOperationException): Throwable =
        if (exception is InvocationTargetException) exception.targetException ?: exception else exception

    private data class Binding(val service: Any, val apiType: Class<*>, val add: Method)

    private data class FieldSpec(
        val id: String,
        val displayName: String,
        val description: String,
        val icon: String,
        val higherIsBetter: Boolean,
    )

    private companion object {
        const val NAMESPACE = "Contract"
        const val FIELD_CLASS_NAME = "org.cubexmc.reputations.api.ReputationField"
        val DESCRIPTOR = OptionalServiceDescriptor(
            pluginName = "Reputations",
            apiClassName = "org.cubexmc.reputations.api.ReputationService",
        )
        val FIELDS = listOf(
            FieldSpec("completed", "Contracts completed", "Contracts completed successfully.", "EMERALD", true),
            FieldSpec("cancelled", "Contracts cancelled", "Contracts cancelled after creation.", "BARRIER", false),
            FieldSpec("expired", "Contracts expired", "Accepted contracts allowed to expire.", "CLOCK", false),
            FieldSpec("disputed", "Contract disputes", "Open disputes attributed to this player.", "REDSTONE", false),
        )
    }
}
