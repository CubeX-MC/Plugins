package org.cubexmc.i18n;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public final class I18nOptions {

    private String languageDirectory = "lang";
    private String currentLocale = "zh_CN";
    private Supplier<String> currentLocaleSupplier;
    private String defaultLocale = "zh_CN";
    private final List<String> fallbackLocales = new ArrayList<>();
    private final List<String> bundledLocales = new ArrayList<>();
    private String prefixKey = "prefix";
    private String prefixToken = "%prefix%";
    private String keyPrefix = "";
    private MissingKeyMode missingKeyMode = MissingKeyMode.RETURN_KEY;
    private boolean warnOnMissingKey;
    private Set<PlaceholderStyle> placeholderStyles = EnumSet.of(PlaceholderStyle.PERCENT_NAME);
    private ColorMode colorMode = ColorMode.LEGACY_AND_HEX;

    private I18nOptions() {
    }

    public static I18nOptions create() {
        return new I18nOptions();
    }

    public I18nOptions languageDirectory(String directory) {
        this.languageDirectory = directory == null || directory.isBlank() ? "lang" : directory;
        return this;
    }

    public I18nOptions currentLocale(String locale) {
        this.currentLocale = locale;
        this.currentLocaleSupplier = null;
        return this;
    }

    public I18nOptions currentLocale(Supplier<String> localeSupplier) {
        this.currentLocaleSupplier = localeSupplier;
        return this;
    }

    public I18nOptions defaultLocale(String locale) {
        this.defaultLocale = locale;
        return this;
    }

    public I18nOptions fallbackLocales(List<String> locales) {
        this.fallbackLocales.clear();
        if (locales != null) {
            this.fallbackLocales.addAll(locales);
        }
        return this;
    }

    public I18nOptions bundledLocales(Collection<String> locales) {
        this.bundledLocales.clear();
        if (locales != null) {
            this.bundledLocales.addAll(locales);
        }
        return this;
    }

    public I18nOptions prefixKey(String key) {
        this.prefixKey = key == null ? "" : key;
        return this;
    }

    public I18nOptions prefixToken(String token) {
        this.prefixToken = token == null ? "" : token;
        return this;
    }

    public I18nOptions keyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
        return this;
    }

    public I18nOptions missingKeyMode(MissingKeyMode mode) {
        this.missingKeyMode = mode == null ? MissingKeyMode.RETURN_KEY : mode;
        return this;
    }

    public I18nOptions warnOnMissingKey(boolean enabled) {
        this.warnOnMissingKey = enabled;
        return this;
    }

    public I18nOptions placeholderStyles(Collection<PlaceholderStyle> styles) {
        if (styles == null || styles.isEmpty()) {
            this.placeholderStyles = EnumSet.noneOf(PlaceholderStyle.class);
        } else {
            this.placeholderStyles = EnumSet.copyOf(styles);
        }
        return this;
    }

    public I18nOptions colorize(boolean enabled) {
        this.colorMode = enabled ? ColorMode.LEGACY_AND_HEX : null;
        return this;
    }

    public I18nOptions colorMode(ColorMode mode) {
        this.colorMode = mode;
        return this;
    }

    String languageDirectory() {
        return languageDirectory;
    }

    String currentLocale() {
        String supplied = currentLocaleSupplier == null ? null : currentLocaleSupplier.get();
        return supplied == null || supplied.isBlank() ? currentLocale : supplied;
    }

    boolean hasCurrentLocaleSupplier() {
        return currentLocaleSupplier != null;
    }

    String defaultLocale() {
        return defaultLocale;
    }

    List<String> fallbackLocales() {
        return Collections.unmodifiableList(fallbackLocales);
    }

    List<String> bundledLocales() {
        return Collections.unmodifiableList(bundledLocales);
    }

    String prefixKey() {
        return prefixKey;
    }

    String prefixToken() {
        return prefixToken;
    }

    String keyPrefix() {
        return keyPrefix;
    }

    MissingKeyMode missingKeyMode() {
        return missingKeyMode;
    }

    boolean warnOnMissingKey() {
        return warnOnMissingKey;
    }

    Set<PlaceholderStyle> placeholderStyles() {
        return Collections.unmodifiableSet(placeholderStyles);
    }

    ColorMode colorMode() {
        return colorMode;
    }
}
