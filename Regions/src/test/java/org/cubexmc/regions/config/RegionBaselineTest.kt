package org.cubexmc.regions.config

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class RegionBaselineTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `current first release baseline is accepted`() {
        for (baseline in RegionBaseline.files) {
            write(baseline.path, baseline.versionKey, baseline.version)
        }

        assertEquals(emptyList<String>(), RegionBaseline.validate(tempDir.toFile()))
    }

    @Test
    fun `pre-release development formats are rejected instead of silently migrated`() {
        for (baseline in RegionBaseline.files) {
            write(baseline.path, baseline.versionKey, baseline.version)
        }
        write("regions.yml", "regions-version", 2)

        val errors = RegionBaseline.validate(tempDir.toFile())

        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("requires 4"))
    }

    private fun write(path: String, key: String, version: Int) {
        val file = tempDir.resolve(path).toFile()
        file.parentFile.mkdirs()
        YamlConfiguration().apply { set(key, version) }.save(file)
    }
}
