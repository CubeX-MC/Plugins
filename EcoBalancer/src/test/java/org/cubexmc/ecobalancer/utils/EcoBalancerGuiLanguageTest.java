package org.cubexmc.ecobalancer.utils;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class EcoBalancerGuiLanguageTest {
    @Test
    void bothBundledLocalesCoverEveryGuiKeyUsedByGuiManager() throws Exception {
        Path sourcePath = projectPath("src/main/java/org/cubexmc/ecobalancer/gui/GuiManager.kt");
        String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
        Matcher references = Pattern.compile("messages\\.gui\\.([A-Za-z0-9_]+)").matcher(source);
        Set<String> keys = new HashSet<>();
        while (references.find()) {
            keys.add(references.group(1));
        }

        for (String locale : List.of("en_US", "zh_CN")) {
            YamlConfiguration language = YamlConfiguration.loadConfiguration(
                    projectPath("src/main/resources/lang/" + locale + ".yml").toFile());
            List<String> missing = new ArrayList<>();
            for (String key : keys) {
                if (language.getString("messages.gui." + key) == null) {
                    missing.add(key);
                }
            }
            assertTrue(missing.isEmpty(), () -> locale + " is missing GUI keys: " + missing);
        }
    }

    private Path projectPath(String relative) {
        Path path = Path.of(relative);
        return Files.exists(path) ? path : Path.of("EcoBalancer").resolve(relative);
    }
}
