package org.cubexmc.metro.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class EntityDisplayConfigTest {

    @Test
    void shouldLoadDefaultAndEntitySpecificDisplaySettings() throws InvalidConfigurationException {
        EntityDisplayConfig config = EntityDisplayConfig.fromConfig(yaml("""
                defaults:
                  spacing: 1.6
                  height: 0.1
                entities:
                  allay:
                    offset-y: 0.2
                  horse:
                    spacing: 2.6
                    height: 0.4
                    saddle: true
                  minecraft:happy_ghast:
                    spacing: 5.1
                    height: -1.0
                    harness: true
                    goggles: true
                """));

        EntityDisplayConfig.DisplaySettings horse = config.settingsFor("horse");
        assertEquals(2.6, horse.spacing());
        assertEquals(0.4, horse.offsetY());
        assertEquals(true, horse.properties().get("saddle"));

        EntityDisplayConfig.DisplaySettings allay = config.settingsFor("allay");
        assertEquals(1.6, allay.spacing());
        assertEquals(0.2, allay.offsetY());

        EntityDisplayConfig.DisplaySettings happyGhast = config.settingsFor("happy-ghast");
        assertEquals(5.1, happyGhast.spacing());
        assertEquals(-1.0, happyGhast.offsetY());
        assertEquals(true, happyGhast.properties().get("harness"));
        assertEquals(true, happyGhast.properties().get("goggles"));
    }

    @Test
    void shouldMergeDefaultPropertiesIntoEntitySettings() throws InvalidConfigurationException {
        EntityDisplayConfig config = EntityDisplayConfig.fromConfig(yaml("""
                defaults:
                  properties:
                    glowing: false
                entities:
                  pig:
                    saddle: true
                """));

        EntityDisplayConfig.DisplaySettings pig = config.settingsFor("pig");
        assertEquals(false, pig.properties().get("glowing"));
        assertEquals(true, pig.properties().get("saddle"));
    }

    @Test
    void shouldNormalizeFutureEntityNamesWithoutCurrentApiConstant() {
        assertEquals("HAPPY_GHAST", EntityModelController.normalizeConfiguredEntityName("minecraft:happy-ghast"));
        assertTrue(EntityDisplayConfig.empty().settingsFor("happy_ghast").properties().isEmpty());
    }

    private YamlConfiguration yaml(String content) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(content);
        return config;
    }
}
