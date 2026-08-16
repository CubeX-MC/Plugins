package org.cubexmc.i18n

import org.cubexmc.core.CubexPlugin

object I18nServices {
    @JvmStatic
    fun create(plugin: CubexPlugin, options: I18nOptions?): I18nService =
        SimpleI18nService(plugin, options ?: I18nOptions.create())
}
