package org.cubexmc.cookbook.welcomeback

/** 纯逻辑，不依赖 Bukkit —— 单测直接打它。 */
object Welcomes {

    const val FIRST_JOIN: String = "&e欢迎新玩家 &f{name}&e!"

    const val RETURNING: String = "&a欢迎回来, &f{name}&a!"

    fun render(name: String, firstJoin: Boolean): String =
        (if (firstJoin) FIRST_JOIN else RETURNING).replace("{name}", name)
}
