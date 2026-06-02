package org.cubexmc.i18n;

import org.cubexmc.core.CubexPlugin;

public final class I18nServices {

    private I18nServices() {
    }

    public static I18nService create(CubexPlugin plugin, I18nOptions options) {
        return new SimpleI18nService(plugin, options == null ? I18nOptions.create() : options);
    }
}
