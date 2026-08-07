package org.cubexmc.regions.service

import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.integration.RegionSource
import org.cubexmc.regions.integration.RegionSourceRegistry
import org.cubexmc.regions.mode.CombatModeService
import org.cubexmc.regions.mode.RaceModeService
import org.cubexmc.regions.mode.RoundModeService
import org.cubexmc.regions.model.ActionBlockConfig
import org.cubexmc.regions.model.ActionConfig
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionLifecycle
import org.cubexmc.regions.model.RegionSourceRef
import org.cubexmc.regions.model.RegionTrigger
import org.cubexmc.regions.model.ValidationIssue
import org.cubexmc.regions.model.ValidationSeverity
import org.cubexmc.regions.storage.RegionStorage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.nio.file.Path
import java.util.logging.Logger
import java.util.UUID

class RegionPublishingServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var plugin: RegionsPlugin
    private lateinit var storage: RegionStorage
    private lateinit var registry: RegionRegistry
    private lateinit var authority: RegionAuthorityService
    private lateinit var validation: RegionValidationService
    private lateinit var sender: CommandSender
    private lateinit var service: RegionPublishingService
    private lateinit var source: RegionSource
    private val sourceOwner = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        plugin = mock(RegionsPlugin::class.java)
        `when`(plugin.dataFolder).thenReturn(tempDir.toFile())
        `when`(plugin.logger).thenReturn(Logger.getLogger("RegionPublishingServiceTest"))
        storage = RegionStorage(plugin)
        registry = mock(RegionRegistry::class.java)
        authority = mock(RegionAuthorityService::class.java)
        validation = mock(RegionValidationService::class.java)
        sender = mock(CommandSender::class.java)
        source = mock(RegionSource::class.java)
        val sources = mock(RegionSourceRegistry::class.java)
        val config = YamlConfiguration().apply { set("publishing.keep-revisions", 20) }

        `when`(plugin.storage()).thenReturn(storage)
        `when`(plugin.regions()).thenReturn(registry)
        `when`(plugin.authority()).thenReturn(authority)
        `when`(plugin.validation()).thenReturn(validation)
        `when`(plugin.overlaps()).thenReturn(RegionOverlapResolver())
        `when`(plugin.sources()).thenReturn(sources)
        `when`(sources.find("lands")).thenReturn(source)
        `when`(plugin.config).thenReturn(config)
        `when`(plugin.audit()).thenReturn(mock(RegionAuditService::class.java))
        `when`(plugin.combatModes()).thenReturn(mock(CombatModeService::class.java))
        `when`(plugin.raceModes()).thenReturn(mock(RaceModeService::class.java))
        `when`(plugin.roundModes()).thenReturn(mock(RoundModeService::class.java))
        `when`(plugin.sessions()).thenReturn(mock(RegionSessionService::class.java))
        service = RegionPublishingService(plugin)
    }

    @Test
    fun `publishing swaps the validated draft and records both immutable revisions`() {
        val current = region(1, "Published")
        val draft = region(2, "Draft").copy(lifecycle = RegionLifecycle.DRAFT, publishedRevision = 1)
        storage.put(current)
        storage.putDraft(draft)
        `when`(registry.find("arena")).thenReturn(current)
        `when`(authority.canManage(sender, draft)).thenReturn(AuthorityDecision.allow())
        `when`(validation.validate(draft)).thenReturn(emptyList())

        val result = service.publish(sender, "arena")

        assertTrue(result.success)
        val published = storage.find("arena")!!
        assertEquals("Draft", published.name)
        assertEquals(RegionLifecycle.PUBLISHED, published.lifecycle)
        assertEquals(2, published.publishedRevision)
        assertEquals(listOf(1L, 2L), storage.revisionHistory("arena").map { it.revision })
        assertEquals(null, storage.findDraft("arena"))
    }

    @Test
    fun `rollback restores old content as a new revision and keeps current ownership snapshot`() {
        val current = region(3, "Current").copy(metadata = mapOf(RegionAuthorityService.SOURCE_OWNER_METADATA to sourceOwner.toString()))
        val target = region(1, "Original").copy(metadata = mapOf(RegionAuthorityService.SOURCE_OWNER_METADATA to UUID.randomUUID().toString()))
        `when`(registry.find("arena")).thenReturn(current)
        storage.put(current)
        storage.recordRevision(target)
        `when`(authority.canManage(sender, current)).thenReturn(AuthorityDecision.allow())
        `when`(authority.canCreate(sender, target.source)).thenReturn(AuthorityDecision.allow())
        `when`(source.ownerId(target.source)).thenReturn(sourceOwner)
        `when`(validation.validate(target)).thenReturn(emptyList())

        val result = service.rollback(sender, "arena", 1)

        assertTrue(result.success)
        val restored = storage.find("arena")!!
        assertEquals("Original", restored.name)
        assertEquals(4, restored.revision)
        assertEquals(4, restored.publishedRevision)
        assertEquals(sourceOwner.toString(), restored.metadata[RegionAuthorityService.SOURCE_OWNER_METADATA])
    }

    @Test
    fun `failed validation keeps the current published version and draft untouched`() {
        val current = region(1, "Published")
        val draft = region(2, "Broken draft").copy(lifecycle = RegionLifecycle.DRAFT, publishedRevision = 1)
        storage.put(current)
        storage.putDraft(draft)
        `when`(registry.find("arena")).thenReturn(current)
        `when`(authority.canManage(sender, draft)).thenReturn(AuthorityDecision.allow())
        `when`(validation.validate(draft)).thenReturn(
            listOf(ValidationIssue("arena", ValidationSeverity.ERROR, "broken capability")),
        )

        val result = service.publish(sender, "arena")

        assertEquals(false, result.success)
        assertEquals("Published", storage.find("arena")?.name)
        assertEquals("Broken draft", storage.findDraft("arena")?.name)
        assertEquals(emptyList<Long>(), storage.revisionHistory("arena").map { it.revision })
    }

    @Test
    fun `rollback cannot restore a source the ruler no longer owns`() {
        val current = region(3, "Current")
        val target = region(1, "Foreign historical source")
        storage.put(current)
        storage.recordRevision(target)
        `when`(registry.find("arena")).thenReturn(current)
        `when`(authority.canManage(sender, current)).thenReturn(AuthorityDecision.allow())
        `when`(authority.canCreate(sender, target.source)).thenReturn(
            AuthorityDecision.deny(AuthorityDenial.NOT_SOURCE_OWNER),
        )

        val result = service.rollback(sender, "arena", 1)

        assertEquals(false, result.success)
        assertEquals("Current", storage.find("arena")?.name)
        assertEquals(3, storage.find("arena")?.revision)
    }

    @Test
    fun `rulers cannot publish console authority actions`() {
        val draft = region(1, "Unsafe draft").copy(
            lifecycle = RegionLifecycle.DRAFT,
            publishedRevision = null,
            triggers = mapOf(
                RegionTrigger.ON_ENTER to listOf(
                    ActionBlockConfig(thenActions = listOf(
                        ActionConfig("console_command", mapOf("command" to "op {player}")),
                    )),
                ),
            ),
        )
        storage.put(draft)
        storage.putDraft(draft)
        `when`(registry.find("arena")).thenReturn(draft)
        `when`(authority.canManage(sender, draft)).thenReturn(AuthorityDecision.allow())
        `when`(authority.isSuperAdmin(sender)).thenReturn(false)
        `when`(validation.validate(draft)).thenReturn(emptyList())

        val result = service.publish(sender, "arena")

        assertEquals(false, result.success)
        assertTrue(result.reason?.contains("super-administrators") == true)
        assertEquals(RegionLifecycle.DRAFT, storage.find("arena")?.lifecycle)
    }

    private fun region(revision: Long, name: String): RegionDefinition = RegionDefinition(
        id = "arena",
        name = name,
        source = RegionSourceRef("lands", mapOf("land" to "capital", "area" to "arena")),
        revision = revision,
        publishedRevision = revision,
    )
}
