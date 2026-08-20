import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CubexPluginYmlTest {

    @Test
    fun `appends a depend line when plugin yml has none`() {
        val yml = "name: Clarity\nmain: org.cubexmc.clarity.ClarityPlugin\n"

        val result = CubexPluginYml.withDepend(yml, "CubeXLib")

        assertTrue(result.endsWith("depend: [CubeXLib]\n"))
        assertTrue(result.startsWith("name: Clarity"))
    }

    @Test
    fun `merges into an existing inline depend array`() {
        val yml = "name: Contract\ndepend: [Vault]\nsoftdepend: [CMI]\n"

        val result = CubexPluginYml.withDepend(yml, "CubeXLib")

        assertTrue(result.lines().contains("depend: [Vault, CubeXLib]"))
        assertTrue(result.lines().contains("softdepend: [CMI]"))
    }

    @Test
    fun `tolerates the spaced array style already used in the repo`() {
        val yml = "name: FAWEReplacer\ndepend: [ WorldEdit ]\n"

        val result = CubexPluginYml.withDepend(yml, "CubeXLib")

        assertTrue(result.lines().contains("depend: [WorldEdit, CubeXLib]"))
    }

    @Test
    fun `returns the text unchanged when the plugin is already declared`() {
        val yml = "name: Demo\ndepend: [CubeXLib]\n"

        assertEquals(yml, CubexPluginYml.withDepend(yml, "CubeXLib"))
    }

    @Test
    fun `ignores case when deciding whether the plugin is already declared`() {
        val yml = "name: Demo\ndepend: [cubexlib]\n"

        assertEquals(yml, CubexPluginYml.withDepend(yml, "CubeXLib"))
    }

    @Test
    fun `fails loudly on block style depend instead of writing broken yaml`() {
        val yml = "name: Demo\ndepend:\n  - Vault\n"

        val error = assertThrows<IllegalArgumentException> {
            CubexPluginYml.withDepend(yml, "CubeXLib")
        }

        assertTrue(error.message!!.contains("内联数组"))
    }
}
