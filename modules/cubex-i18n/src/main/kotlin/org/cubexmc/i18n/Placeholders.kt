package org.cubexmc.i18n

public object Placeholders {
    @JvmStatic
    public fun empty(): MutableMap<String, Any?> = HashMap()

    @JvmStatic
    public fun of(key: String, value: Any?): MutableMap<String, Any?> = empty().also { it[key] = value }

    @JvmStatic
    public fun put(args: MutableMap<String, Any?>, key: String, value: Any?): MutableMap<String, Any?> =
        args.also { it[key] = value }
}
