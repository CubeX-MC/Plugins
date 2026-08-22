package org.cubexmc.cookbook.helloexternal

/**
 * 纯逻辑放在 Bukkit 之外 —— 这样它能被单测直接覆盖，不需要 mock 服务器。
 * cookbook 的每一篇都按这个形状写。
 */
object Greetings {

    const val DEFAULT_TEMPLATE: String = "&a你好, &f{name}&a!"

    const val NAME_PLACEHOLDER: String = "{name}"

    fun render(template: String, name: String): String = template.replace(NAME_PLACEHOLDER, name)
}
