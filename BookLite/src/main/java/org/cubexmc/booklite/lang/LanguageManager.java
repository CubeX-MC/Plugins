package org.cubexmc.booklite.lang;

import java.io.File;
import java.util.List;
import org.cubexmc.booklite.BookLitePlugin;
import org.cubexmc.i18n.ColorMode;
import org.cubexmc.i18n.I18nOptions;
import org.cubexmc.i18n.I18nServices;
import org.cubexmc.i18n.MissingKeyMode;
import org.cubexmc.i18n.PlaceholderStyle;

public class LanguageManager extends org.cubexmc.i18n.BookLiteLanguageAdapter {

    private final BookLitePlugin plugin;
    private String locale;

    public LanguageManager(BookLitePlugin plugin, String locale) {
        super(I18nServices.create(plugin, I18nOptions.create()
                .languageDirectory("lang")
                .currentLocale(locale)
                .defaultLocale("zh_CN")
                .fallbackLocales(List.of("zh_CN"))
                .bundledLocales(List.of("zh_CN", "en_US"))
                .prefixKey("prefix")
                .prefixToken("{prefix}")
                .missingKeyMode(MissingKeyMode.RETURN_KEY)
                .placeholderStyles(List.of(PlaceholderStyle.PERCENT_NAME))
                .colorMode(ColorMode.LEGACY_AND_HEX)));
        this.plugin = plugin;
        this.locale = locale;
    }

    @Override
    public void setLocale(String locale) {
        this.locale = locale;
        super.setLocale(locale);
    }

    @Override
    public void load() {
        File langFile = new File(plugin.getDataFolder(), "lang" + File.separator + locale + ".yml");
        if (!langFile.exists()) {
            plugin.getLogger().warning("Language file " + locale + ".yml missing, falling back to zh_CN.");
        }
        super.load();
    }
}
