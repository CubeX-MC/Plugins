package org.cubexmc.contract.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Guards the plugin's globalization contract:
 * <ul>
 *   <li>every bundled locale defines exactly the same key set, so switching {@code language}
 *       never silently degrades a screen to raw keys;</li>
 *   <li>every {@code ui.*} key the Kotlin sources ask for actually exists;</li>
 *   <li>translations keep the same {@code <placeholder>} tokens, so no locale drops a value.</li>
 * </ul>
 */
class LanguageParityTest {

    @Test
    void everyContractStatusHasALabelInBothLocales() {
        for (String locale : LOCALES) {
            YamlConfiguration lang = load(locale);
            for (org.cubexmc.contract.model.ContractStatus status : org.cubexmc.contract.model.ContractStatus.values()) {
                assertTrue(lang.isString("status." + status.name().toLowerCase(java.util.Locale.ROOT)),
                    locale + " is missing status " + status);
            }
        }
    }

    private static final List<String> LOCALES = List.of("zh_CN", "en_US");
    private static final Pattern PLACEHOLDER = Pattern.compile("<([a-z][a-z0-9_-]*)>");
    /**
     * The literal immediately inside a {@code ui(...)} call. Anchored on the call rather than the
     * whole line, because a line can legitimately mention other dashed literals — a {@code terms.*}
     * key passed as a placeholder value, for example.
     */
    private static final Pattern UI_KEY = Pattern.compile("\\bui\\(\\s*\"([a-z][a-z0-9-]*)\"");
    private static final Pattern FUNDING_FAIL_KEY = Pattern.compile("\\bfail\\(\\s*\"([a-z][a-z0-9-]*)\"");

    /** The conditional form: {@code ui(if (cond) "a" else "b")}. */
    private static final Pattern UI_KEY_CONDITIONAL = Pattern.compile(
            "\\bui\\(\\s*if\\s*\\([^)]*\\)\\s*\"([a-z][a-z0-9-]*)\"\\s*else\\s*\"([a-z][a-z0-9-]*)\"");

    @Test
    void bundledLocalesDefineTheSameKeys() {
        YamlConfiguration base = load(LOCALES.get(0));
        Set<String> baseKeys = base.getKeys(true);

        for (String locale : LOCALES.subList(1, LOCALES.size())) {
            YamlConfiguration other = load(locale);
            Set<String> otherKeys = other.getKeys(true);

            Set<String> missing = new TreeSet<>(baseKeys);
            missing.removeAll(otherKeys);
            Set<String> extra = new TreeSet<>(otherKeys);
            extra.removeAll(baseKeys);

            assertTrue(missing.isEmpty(), locale + ".yml is missing keys: " + missing);
            assertTrue(extra.isEmpty(), locale + ".yml has keys no other locale defines: " + extra);
        }
    }

    @Test
    void translationsKeepTheSamePlaceholders() {
        YamlConfiguration base = load(LOCALES.get(0));

        for (String locale : LOCALES.subList(1, LOCALES.size())) {
            YamlConfiguration other = load(locale);
            for (String key : base.getKeys(true)) {
                String baseValue = base.getString(key);
                String otherValue = other.getString(key);
                if (baseValue == null || otherValue == null || isCommandSyntax(key)) {
                    continue;
                }
                assertEquals(
                        placeholders(baseValue),
                        placeholders(otherValue),
                        "placeholder mismatch for " + key + " in " + locale + ".yml");
            }
        }
    }

    @Test
    void everyUiKeyReferencedInSourcesIsDefined() throws Exception {
        YamlConfiguration base = load(LOCALES.get(0));
        Set<String> defined = base.getKeys(true).stream()
                .filter(key -> key.startsWith("ui."))
                .map(key -> key.substring("ui.".length()))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> missing = new TreeSet<>();
        Path sources = Path.of("src", "main", "kotlin");
        try (Stream<Path> walk = Files.walk(sources)) {
            List<Path> kotlinFiles = walk.filter(path -> path.toString().endsWith(".kt")).toList();
            for (Path file : kotlinFiles) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher simple = UI_KEY.matcher(source);
                while (simple.find()) {
                    record(defined, missing, simple.group(1), file);
                }
                Matcher conditional = UI_KEY_CONDITIONAL.matcher(source);
                if (file.getFileName().toString().equals("AllianceFundingService.kt")) {
                    Matcher failures = FUNDING_FAIL_KEY.matcher(source);
                    while (failures.find()) record(defined, missing, failures.group(1), file);
                }
                while (conditional.find()) {
                    record(defined, missing, conditional.group(1), file);
                    record(defined, missing, conditional.group(2), file);
                }
            }
        }

        assertTrue(missing.isEmpty(), "ui keys used in code but not defined in lang files: " + missing);
    }

    @Test
    void noKotlinSourceEmitsLegacyColourCodes() throws Exception {
        // GuiItems no longer translates colours — every string reaches the player exactly as the
        // i18n service rendered it. A stray "&#RRGGBB" literal in Kotlin would therefore print
        // verbatim instead of colouring, which is easy to miss by eye and impossible to miss here.
        Pattern legacyColour = Pattern.compile("\"[^\"]*&#[0-9A-Fa-f]{6}");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Path.of("src", "main", "kotlin"))) {
            for (Path file : walk.filter(path -> path.toString().endsWith(".kt")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int index = 0; index < lines.size(); index++) {
                    String line = lines.get(index);
                    if (line.stripLeading().startsWith("*") || line.stripLeading().startsWith("//")) {
                        continue;
                    }
                    if (legacyColour.matcher(line).find()) {
                        offenders.add(file.getFileName() + ":" + (index + 1));
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "legacy &#RRGGBB colour literals must live in the language files, not in Kotlin: " + offenders);
    }

    private void record(Set<String> defined, Set<String> missing, String key, Path file) {
        if (!defined.contains(key)) {
            missing.add(key + "  (" + file.getFileName() + ")");
        }
    }

    /**
     * Usage lines and the help block spell out command syntax like {@code <reward|item> <days>};
     * those angle brackets are argument names shown to the player, not substitution placeholders,
     * and each locale names them in its own language.
     */
    private boolean isCommandSyntax(String key) {
        return key.startsWith("ui.usage-") || key.equals("messages.help") || key.equals("messages.list-footer");
    }

    private Set<String> placeholders(String value) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        // <prefix> is substituted by the i18n layer, not by the caller.
        found.remove("prefix");
        return found;
    }

    private YamlConfiguration load(String locale) {
        File file = new File("src/main/resources/lang/" + locale + ".yml");
        assertTrue(file.isFile(), "missing bundled locale: " + file);
        return YamlConfiguration.loadConfiguration(file);
    }
}
