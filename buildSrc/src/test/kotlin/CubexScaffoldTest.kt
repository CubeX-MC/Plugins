import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CubexScaffoldTest {

    @Test
    fun `rejects names that are not upper camel case`() {
        assertThrows<IllegalArgumentException> { CubexScaffold.requireValidName("my-plugin") }
        assertThrows<IllegalArgumentException> { CubexScaffold.requireValidName("myPlugin") }
        assertEquals("MyPlugin", CubexScaffold.requireValidName("MyPlugin"))
    }

    @Test
    fun `derives package and plugin id from the name`() {
        assertEquals("org.cubexmc.myplugin", CubexScaffold.defaultPackage("MyPlugin"))
        assertEquals("myplugin", CubexScaffold.pluginId("MyPlugin"))
    }

    @Test
    fun `always puts core first and drops duplicates`() {
        assertEquals(listOf("core", "i18n", "config"), CubexScaffold.normalizeModules(listOf("i18n", "core", "config", "i18n")))
    }

    @Test
    fun `rejects unknown modules with the full candidate list`() {
        val error = assertThrows<IllegalArgumentException> { CubexScaffold.normalizeModules(listOf("effect")) }

        assertTrue(error.message!!.contains("effect"))
        assertTrue(error.message!!.contains("scheduler"))
    }

    @Test
    fun `external build script opts into the packaging mode, embedded does not`() {
        val external = CubexScaffold.buildScript("Demo", CubexPackagingMode.EXTERNAL, listOf("core"))
        val embedded = CubexScaffold.buildScript("Demo", CubexPackagingMode.EMBEDDED, listOf("core"))

        assertTrue(external.contains("cubex { packaging.set(CubexPackagingMode.EXTERNAL) }"))
        assertFalse(embedded.contains("packaging.set"))
        assertTrue(embedded.contains("""implementation(project(":modules:cubex-core"))"""))
    }

    @Test
    fun `scaffold refuses to generate the lib mode`() {
        assertThrows<IllegalArgumentException> {
            CubexScaffold.buildScript("Demo", CubexPackagingMode.LIB, listOf("core"))
        }
    }

    @Test
    fun `generated plugin yml never hardcodes the CubeXLib depend`() {
        val yml = CubexScaffold.pluginYml("Demo", "org.cubexmc.demo", CubexPackagingMode.EXTERNAL)

        assertTrue(yml.contains("main: org.cubexmc.demo.DemoPlugin"))
        assertFalse(yml.contains("depend: ["))
    }

    @Test
    fun `registers the project in the settings plugin list`() {
        val settings = """listOf("BookLite", "StateCharge").forEach {"""

        val result = CubexScaffold.withSettingsEntry(settings, "Demo")

        assertEquals("""listOf("BookLite", "StateCharge", "Demo").forEach {""", result)
    }

    @Test
    fun `settings registration is idempotent`() {
        val settings = """listOf("BookLite", "Demo").forEach {"""

        assertEquals(settings, CubexScaffold.withSettingsEntry(settings, "Demo"))
    }

    @Test
    fun `registers the plugin id in the relocations map`() {
        val relocations = "    private val pluginIds = mapOf(\n        \"BookLite\" to \"booklite\",\n    )\n"

        val result = CubexScaffold.withRelocationEntry(relocations, "Demo", "demo")

        assertTrue(result.contains("\"Demo\" to \"demo\","))
        assertTrue(result.indexOf("\"Demo\"") < result.indexOf("    )"))
    }

    @Test
    fun `relocation registration is idempotent`() {
        val relocations = "    private val pluginIds = mapOf(\n        \"Demo\" to \"demo\",\n    )\n"

        assertEquals(relocations, CubexScaffold.withRelocationEntry(relocations, "Demo", "demo"))
    }
}
