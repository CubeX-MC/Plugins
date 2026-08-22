package org.cubexmc.regions.service

import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import org.cubexmc.regions.integration.RegionSource
import org.cubexmc.regions.integration.RegionSourceRegistry
import org.cubexmc.regions.model.ExternalRegion
import org.cubexmc.regions.model.ModeConfig
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
    fun `a fine-grained node grants exactly one global action`() {
        // plugin.yml 早就声明了 regions.reload / inspect / cleanup,但此前谁也没检查过 ——
        // 只给这些节点等于什么都没给。现在它们能单独授权,不必发整个 regions.superadmin。
        val service = service()
        val reloader = player(UUID.randomUUID(), nodes = setOf("regions.reload"))

        assertTrue(service.canUseGlobalAdministration(reloader, "regions.reload").allowed)
        assertEquals(
            AuthorityDenial.SUPERADMIN_REQUIRED,
            service.canUseGlobalAdministration(reloader, "regions.cleanup").denial,
        )
        assertEquals(AuthorityDenial.SUPERADMIN_REQUIRED, service.canUseGlobalAdministration(reloader).denial)
    }

    @Test
    fun `rulers get no implicit access to global administration`() {
        // 全服级操作和"能管自己的场地"不是一回事:统治者在这条路径上没有旁路,
        // 与 canManage 的语义刻意不同。
        val service = service()
        val ruler = player(UUID.randomUUID(), ruler = true)

        assertEquals(
            AuthorityDenial.SUPERADMIN_REQUIRED,
            service.canUseGlobalAdministration(ruler, "regions.reload").denial,
        )
    }

    @Test
    fun `superadmin still passes without holding any fine-grained node`() {
        val service = service()
        val superAdmin = player(UUID.randomUUID(), superAdmin = true)

        assertTrue(service.canUseGlobalAdministration(superAdmin, "regions.reload").allowed)
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

    @Test
    fun `judges may start and end a round without gaining any management rights`() {
        val ownerId = UUID.randomUUID()
        val judgeId = UUID.randomUUID()
        val service = service(FakeSource("lands", owners = mutableSetOf(ownerId)))
        val venue = withJudges(region(), judgeId)

        val owner = player(ownerId, ruler = true)
        val judge = player(judgeId)
        val bystander = player(UUID.randomUUID())

        assertTrue(service.canJudge(owner, venue).allowed, "the venue owner always runs their own event")
        assertTrue(service.canJudge(judge, venue).allowed)
        assertFalse(service.canJudge(bystander, venue).allowed)

        // 裁判权限只覆盖开赛/结束——改配置、发布、删场地仍然只有场主能做。
        assertFalse(service.canManage(judge, venue).allowed)
        assertEquals(AuthorityDenial.NOT_RULER, service.canManage(judge, venue).denial)
    }

    @Test
    fun `a frozen venue strips the judges too`() {
        val ownerId = UUID.randomUUID()
        val judgeId = UUID.randomUUID()
        val service = service(FakeSource("lands", owners = mutableSetOf(ownerId)))
        val frozen = withJudges(region(), judgeId).copy(lifecycle = RegionLifecycle.FROZEN)

        assertEquals(AuthorityDenial.REGION_FROZEN, service.canJudge(player(judgeId), frozen).denial)
        assertEquals(AuthorityDenial.REGION_FROZEN, service.canJudge(player(ownerId, ruler = true), frozen).denial)
    }

    @Test
    fun `only well formed uuids count as judges`() {
        val ownerId = UUID.randomUUID()
        val judgeId = UUID.randomUUID()
        val service = service(FakeSource("lands", owners = mutableSetOf(ownerId)))

        // 名单存 UUID。旧版本按玩家名写下的条目会被忽略——按名字匹配在改名后失效，
        // 而且旧名可能被别人注册走，等于把发令权转给陌生人。
        val byName = region().copy(mode = ModeConfig("run_race", mapOf("judges" to "Steve,Alex")))
        assertTrue(service.judgeIds(byName).isEmpty())
        assertFalse(service.canJudge(player(judgeId), byName).allowed)

        val mixed = region().copy(mode = ModeConfig("run_race", mapOf("judges" to "Steve, $judgeId ;")))
        assertEquals(setOf(judgeId), service.judgeIds(mixed))
        assertTrue(service.canJudge(player(judgeId), mixed).allowed)
    }

    private fun withJudges(region: RegionDefinition, vararg judges: UUID): RegionDefinition =
        region.copy(mode = ModeConfig("run_race", mapOf("judges" to judges.joinToString(",") { it.toString() })))

    private fun service(vararg sources: RegionSource): RegionAuthorityService {
        val registry = RegionSourceRegistry()
        sources.forEach(registry::register)
        return RegionAuthorityService(registry)
    }

    private fun region(
        id: String = "venue",
        source: RegionSourceRef = RegionSourceRef("lands", mapOf("land" to "capital", "area" to "default")),
    ): RegionDefinition = RegionDefinition(id = id, name = id, source = source)

    private fun player(
        id: UUID,
        ruler: Boolean = false,
        superAdmin: Boolean = false,
        nodes: Set<String> = emptySet(),
    ): Player {
        val player = mock(Player::class.java)
        `when`(player.uniqueId).thenReturn(id)
        `when`(player.hasPermission(RegionAuthorityService.RULER_PERMISSION)).thenReturn(ruler)
        `when`(player.hasPermission(RegionAuthorityService.SUPERADMIN_PERMISSION)).thenReturn(superAdmin)
        for (node in nodes) {
            `when`(player.hasPermission(node)).thenReturn(true)
        }
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
