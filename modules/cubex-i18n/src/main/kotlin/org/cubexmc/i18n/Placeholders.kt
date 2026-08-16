package org.cubexmc.i18n

object Placeholders {
    @JvmStatic
    fun empty(): MutableMap<String, Any?> = HashMap()

    @JvmStatic
    fun of(key: String, value: Any?): MutableMap<String, Any?> = empty().also { it[key] = value }

    @JvmStatic
    fun put(args: MutableMap<String, Any?>, key: String, value: Any?): MutableMap<String, Any?> =
        args.also { it[key] = value }
}
