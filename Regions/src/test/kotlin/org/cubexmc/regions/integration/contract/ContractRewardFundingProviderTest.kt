package org.cubexmc.regions.integration.contract

import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.ServicesManager
import org.cubexmc.contract.api.escrow.ContractEscrowResult
import org.cubexmc.contract.api.escrow.ContractEscrowService
import org.cubexmc.core.CubexLogger
import org.cubexmc.integrations.OptionalServiceConnector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.util.UUID
import java.util.logging.Logger

class ContractRewardFundingProviderTest {
    @Test
    fun `uses provider classloader API without a Regions compile dependency`() {
        val plugins = mock(PluginManager::class.java)
        val services = mock(ServicesManager::class.java)
        val providerPlugin = mock(Plugin::class.java)
        val partyA = UUID.randomUUID()
        val partyB = UUID.randomUUID()
        val service = RecordingService(partyA, partyB)
        `when`(plugins.getPlugin("Contract")).thenReturn(providerPlugin)
        `when`(providerPlugin.isEnabled).thenReturn(true)
        `when`(services.load(ContractEscrowService::class.java)).thenReturn(service)
        val provider = ContractRewardFundingProvider(
            OptionalServiceConnector(plugins, services),
            CubexLogger(Logger.getLogger("ContractRewardFundingProviderTest")),
        )

        val checked = provider.check("wager", "arena")
        val settled = provider.settle("op", "wager", "arena", partyB)

        assertTrue(checked.successful)
        assertEquals(partyA, checked.partyA)
        assertEquals(partyB, checked.partyB)
        assertTrue(settled.successful)
        assertEquals(partyB, service.winner)
    }

    @Test
    fun `missing Contract is an unavailable optional capability`() {
        val plugins = mock(PluginManager::class.java)
        val services = mock(ServicesManager::class.java)
        val provider = ContractRewardFundingProvider(
            OptionalServiceConnector(plugins, services),
            CubexLogger(Logger.getLogger("ContractRewardFundingProviderTest")),
        )

        val result = provider.check("wager", "arena")

        assertFalse(result.successful)
        assertEquals("PROVIDER_UNAVAILABLE", result.code)
        verifyNoInteractions(services)
    }

    private class RecordingService(private val partyA: UUID, private val partyB: UUID) : ContractEscrowService {
        var winner: UUID? = null

        override fun check(contractId: String, regionId: String) = result(contractId)
        override fun lock(operationId: String, contractId: String, regionId: String) = result(contractId)
        override fun settle(operationId: String, contractId: String, regionId: String, winnerId: UUID): ContractEscrowResult {
            winner = winnerId
            return result(contractId)
        }
        override fun refund(operationId: String, contractId: String, regionId: String, reason: String) = result(contractId)

        private fun result(contractId: String) = ContractEscrowResult(true, contractId, partyA, partyB)
    }
}
