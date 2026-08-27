import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CubexJarClassesTest {
    private val shared = CubexModules.archivePrefixes + CubexModules.relocatedArchivePrefixes("Clarity")
    private val shaded = listOf("org/cubexmc/clarity/libs/")

    @Test
    fun `relocated shared modules retain Java 17 validation inside a Java 21 plugin`() {
        assertEquals(61, expected("org/cubexmc/clarity/libs/cubex/core/CubexPlugin.class"))
        assertEquals(61, expected("org/cubexmc/core/CubexPlugin.class"))
        assertEquals(65, expected("org/cubexmc/clarity/Clarity.class"))
    }

    @Test
    fun `third party libraries and non class resources are excluded`() {
        assertNull(expected("org/cubexmc/clarity/libs/kotlin/Unit.class"))
        assertNull(expected("org/sqlite/JDBC.class"))
        assertNull(expected("org/cubexmc/clarity/libs/cubex/core/config.yml"))
    }

    @Test
    fun `business GUI package is not a shared library class`() {
        assertEquals(65, expected("org/cubexmc/rulegems/gui/GUIManager.class"))
    }

    @Test
    fun `gate rejects prefix collisions that move business commands into the shared namespace`() {
        val namespace = "org/cubexmc/clarity/libs/cubex/"
        org.junit.jupiter.api.Assertions.assertTrue(
            CubexJarClasses.isUnexpectedSharedClass(
                namespace + "commands/CloudCommandManager.class", namespace, shared,
            ),
        )
        org.junit.jupiter.api.Assertions.assertFalse(
            CubexJarClasses.isUnexpectedSharedClass(namespace + "command/CommandMaps.class", namespace, shared),
        )
        org.junit.jupiter.api.Assertions.assertFalse(
            CubexJarClasses.isUnexpectedSharedClass("org/cubexmc/commands/CloudCommandManager.class", namespace, shared),
        )
    }

    private fun expected(path: String) = CubexJarClasses.expectedMajor(path, 65, 61, shared, shaded)
}
