package org.cubexmc.regions.integration.contract

import org.cubexmc.core.CubexLogger
import org.cubexmc.integrations.OptionalServiceConnection
import org.cubexmc.integrations.OptionalServiceConnector
import org.cubexmc.integrations.OptionalServiceDescriptor
import org.cubexmc.regions.reward.FundingResult
import org.cubexmc.regions.reward.RewardFundingProvider
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.UUID

class ContractRewardFundingProvider(
    private val connector: OptionalServiceConnector,
    private val logger: CubexLogger,
) : RewardFundingProvider {
    @Volatile
    private var current: Binding? = null
    private var lastFailure: String? = null

    override fun check(contractId: String, regionId: String): FundingResult =
        invoke("check", contractId, regionId)

    override fun lock(operationId: String, contractId: String, regionId: String): FundingResult =
        invoke("lock", operationId, contractId, regionId)

    override fun settle(
        operationId: String,
        contractId: String,
        regionId: String,
        winnerId: UUID,
    ): FundingResult = invoke("settle", operationId, contractId, regionId, winnerId)

    override fun refund(
        operationId: String,
        contractId: String,
        regionId: String,
        reason: String,
    ): FundingResult = invoke("refund", operationId, contractId, regionId, reason)

    private fun invoke(methodName: String, vararg arguments: Any): FundingResult {
        val binding = safeBinding() ?: return FundingResult.fail("PROVIDER_UNAVAILABLE", "Contract escrow service is unavailable.")
        val method = binding.operations[methodName]
            ?: return FundingResult.fail("API_INCOMPATIBLE", "Contract escrow operation $methodName is missing.")
        return try {
            parse(binding, method.invoke(binding.service, *arguments))
        } catch (ex: ReflectiveOperationException) {
            invalidate(binding, "Contract escrow invocation failed", rootCause(ex))
            FundingResult.fail("PROVIDER_FAILURE", rootCause(ex).message.orEmpty())
        } catch (ex: LinkageError) {
            invalidate(binding, "Contract escrow API became binary-incompatible", ex)
            FundingResult.fail("API_INCOMPATIBLE", ex.message.orEmpty())
        }
    }

    private fun safeBinding(): Binding? = try {
        resolveBinding()
    } catch (ex: Exception) {
        incompatible("Contract escrow discovery failed", ex)
        null
    } catch (ex: LinkageError) {
        incompatible("Contract escrow discovery became binary-incompatible", ex)
        null
    }

    @Synchronized
    private fun resolveBinding(): Binding? = when (val connection = connector.connect(DESCRIPTOR)) {
        is OptionalServiceConnection.Unavailable -> {
            current = null
            null
        }
        is OptionalServiceConnection.Connected -> current?.takeIf {
            it.service === connection.service && it.apiType === connection.apiType
        } ?: bind(connection)
    }

    private fun bind(connection: OptionalServiceConnection.Connected): Binding? = try {
        val api = connection.apiType
        val resultType = Class.forName(RESULT_CLASS_NAME, false, connection.provider.javaClass.classLoader)
        Binding(
            service = connection.service,
            apiType = api,
            operations = mapOf(
                "check" to api.getMethod("check", String::class.java, String::class.java),
                "lock" to api.getMethod("lock", String::class.java, String::class.java, String::class.java),
                "settle" to api.getMethod("settle", String::class.java, String::class.java, String::class.java, UUID::class.java),
                "refund" to api.getMethod("refund", String::class.java, String::class.java, String::class.java, String::class.java),
            ),
            successful = resultType.getMethod("successful"),
            code = resultType.getMethod("code"),
            detail = resultType.getMethod("detail"),
            contractId = resultType.getMethod("contractId"),
            partyA = resultType.getMethod("partyA"),
            partyB = resultType.getMethod("partyB"),
        ).also {
            current = it
            lastFailure = null
        }
    } catch (ex: ReflectiveOperationException) {
        incompatible("Contract escrow API is incompatible with Regions", rootCause(ex))
        null
    } catch (ex: LinkageError) {
        incompatible("Contract escrow API could not be linked by Regions", ex)
        null
    }

    private fun parse(binding: Binding, raw: Any?): FundingResult {
        if (raw == null) return FundingResult.fail("PROVIDER_FAILURE", "Contract returned no result.")
        return FundingResult(
            successful = binding.successful.invoke(raw) as Boolean,
            code = binding.code.invoke(raw).toString(),
            detail = binding.detail.invoke(raw) as String,
            contractId = binding.contractId.invoke(raw) as String?,
            partyA = binding.partyA.invoke(raw) as UUID?,
            partyB = binding.partyB.invoke(raw) as UUID?,
        )
    }

    @Synchronized
    private fun invalidate(binding: Binding, message: String, failure: Throwable) {
        if (current === binding) current = null
        incompatible(message, failure)
    }

    private fun incompatible(message: String, failure: Throwable) {
        val signature = "$message: ${failure.javaClass.name}: ${failure.message.orEmpty()}"
        if (signature != lastFailure) {
            lastFailure = signature
            logger.warn("$message; contract-backed rewards are unavailable.", failure)
        }
    }

    private fun rootCause(exception: ReflectiveOperationException): Throwable =
        if (exception is InvocationTargetException) exception.targetException ?: exception else exception

    private data class Binding(
        val service: Any,
        val apiType: Class<*>,
        val operations: Map<String, Method>,
        val successful: Method,
        val code: Method,
        val detail: Method,
        val contractId: Method,
        val partyA: Method,
        val partyB: Method,
    )

    private companion object {
        val DESCRIPTOR = OptionalServiceDescriptor("Contract", "org.cubexmc.contract.api.escrow.ContractEscrowService")
        const val RESULT_CLASS_NAME = "org.cubexmc.contract.api.escrow.ContractEscrowResult"
    }
}
