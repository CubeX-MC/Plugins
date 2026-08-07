package org.cubexmc.regions.service

import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import org.cubexmc.regions.integration.RegionSource
import org.cubexmc.regions.integration.RegionSourceRegistry
import org.cubexmc.regions.model.ExternalRegion
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionLifecycle
import org.cubexmc.regions.model.RegionSourceRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID

class RegionAuthorityServiceTest {
    @Test
    fun `management requires ruler and current source owner`() {
        val ownerId = UUID.randomUUID()
        val source = FakeSource("lands", owners = mutableSetOf(ownerId))
        val service = service(source)

        val ownerRuler = player(ownerId, ruler = true)
        val ownerNotRuler = player(ownerId, ruler = false)
        val rulerNotOwner = player(UUID.randomUUID(), ruler = true)

        assertTrue(service.canManage(ownerRuler, region()).allowed)
        assertEquals(AuthorityDenial.NOT_RULER, service.canManage(ownerNotRuler, region()).denial)
        assertEquals(AuthorityDenial.NOT_SOURCE_OWNER, service.canManage(rulerNotOwner, region()).denial)
    }

    @Test
    fun `superadmin bypasses ownership and source availability`() {
        val source = FakeSource("lands", available = false)
        val service = service(source)
        val superAdmin = player(UUID.randomUUID(), superAdmin = true)

        assertTrue(service.canManage(superAdmin, region()).allowed)
        assertTrue(service.canCreate(superAdmin, RegionSourceRef("missing")).allowed)
        assertTrue(service.canUseGlobalAdministration(superAdmin).allowed)
    }

    @Test
    fun `ruler receives stable source denial reasons`() {
        val player = player(UUID.randomUUID(), ruler = true)
        val unavailable = service(FakeSource("lands", available = false))
        val missing = service()

        assertEquals(AuthorityDenial.SOURCE_UNAVAILABLE, unavailable.canManage(player, region()).denial)
        assertEquals(AuthorityDenial.SOURCE_UNKNOWN, missing.canManage(player, region()).denial)
        assertEquals(AuthorityDenial.SUPERADMIN_REQUIRED, unavailable.canUseGlobalAdministration(player).denial)
    }

    @Test
    fun `visible regions contain only owned sources for rulers`() {
        val playerId = UUID.randomUUID()
        val source = FakeSource("lands", owners = mutableSetOf(playerId))
        val service = service(source)
        val ruler = player(playerId, ruler = true)
        val owned = region("owned")
        val foreign = region("foreign", RegionSourceRef("other"))

        assertEquals(listOf(owned), service.visibleRegions(ruler, listOf(owned, foreign)))
    }

    @Test
    fun `console is treated as emergency administrator`() {
        val service = service()
        val console = mock(ConsoleCommandSender::class.java)

        assertTrue(service.isSuperAdmin(console))
        assertTrue(service.canUseGlobalAdministration(console).allowed)
    }

    @Test
    fun `frozen region is immutable until superadmin uses lifecycle operation`() {
        val ownerId = UUID.randomUUID()
        val service = service(FakeSource("lands", owners = mutableSetOf(ownerId)))
        val frozen = region().copy(lifecycle = RegionLifecycle.FROZEN)

        assertEquals(AuthorityDenial.REGION_FROZEN, service.canManage(player(ownerId, ruler = true), frozen).denial)
        val superAdmin = player(UUID.randomUUID(), superAdmin = true)
        assertEquals(AuthorityDenial.REGION_FROZEN, service.canManage(superAdmin, frozen).denial)
        assertTrue(service.canUseGlobalAdministration(superAdmin).allowed)
        assertTrue(service.canView(superAdmin, frozen).allowed)
    }

    @Test
    fun `ownership snapshot mismatch fails closed`() {
        val oldOwner = UUID.randomUUID()
        val newOwner = UUID.randomUUID()
        val service = service(FakeSource("lands", owners = mutableSetOf(newOwner)))
        val changed = region().copy(metadata = mapOf(RegionAuthorityService.SOURCE_OWNER_METADATA to oldOwner.toString()))

        assertEquals(AuthorityDenial.OWNERSHIP_CHANGED, service.canManage(player(newOwner, ruler = true), changed).denial)
    }

    private fun service(vararg sources: RegionSource): RegionAuthorityService {
        val registry = RegionSourceRegistry()
        sources.forEach(registry::register)
        return RegionAuthorityService(registry)
    }

    private fun region(
        id: String = "venue",
        source: RegionSourceRef = RegionSourceRef("lands", mapOf("land" to "capital", "area" to "default")),
    ): RegionDefinition = RegionDefinition(id = id, name = id, source = source)

    private fun player(id: UUID, ruler: Boolean = false, superAdmin: Boolean = false): Player {
        val player = mock(Player::class.java)
        `when`(player.uniqueId).thenReturn(id)
        `when`(player.hasPermission(RegionAuthorityService.RULER_PERMISSION)).thenReturn(ruler)
        `when`(player.hasPermission(RegionAuthorityService.SUPERADMIN_PERMISSION)).thenReturn(superAdmin)
        return player
    }

    private class FakeSource(
        override val type: String,
        private val available: Boolean = true,
        private val owners: MutableSet<UUID> = mutableSetOf(),
    ) : RegionSource {
        override fun isAvailable(): Boolean = available

        override fun resolve(ref: RegionSourceRef): ExternalRegion? =
            if (available) ExternalRegion(ref.describe(), ref.describe(), type) else null

        override fun contains(ref: RegionSourceRef, location: Location): Boolean = false

        override fun getOwnedRegions(playerId: UUID): List<ExternalRegion> = emptyList()

        override fun ownerId(ref: RegionSourceRef): UUID? = owners.singleOrNull()

        override fun isOwner(ref: RegionSourceRef, playerId: UUID): Boolean = owners.contains(playerId)
    }
}
