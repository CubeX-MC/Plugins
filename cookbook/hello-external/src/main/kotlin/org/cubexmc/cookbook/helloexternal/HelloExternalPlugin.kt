package org.cubexmc.cookbook.helloexternal

import org.bukkit.command.CommandExecutor
import org.cubexmc.config.YamlFiles
import org.cubexmc.core.CubexPlugin

/**
 * Cookbook 01 —— **外置模式**的最小插件。
 *
 * 看点不在业务，而在这三件事：
 *
 * 1. 代码与内嵌模式**一模一样** —— `CubexPlugin`、`saveResourcesIfMissing`、`YamlFiles`、
 *    `text()`、`messager()` 全是原包名调用。打包模式只写在 `build.gradle.kts` 一行里。
 * 2. jar 里**没有** `cubex-*`，也没有 Kotlin stdlib：运行时由 CubeXLib 提供（`jarGate` 会强制校验）。
 * 3. `plugin.yml` 里**没有** `depend`：由构建按模式注入，手写必然与实际打包漂移。
 *
 * 对外发布的插件不要照抄本篇的打包模式 —— 那种插件必须是内嵌模式，见 `AGENTS.md` 硬约束。
 */
class HelloExternalPlugin : CubexPlugin() {

    private var greetingTemplate: String = Greetings.DEFAULT_TEMPLATE

    override fun enablePlugin() {
        saveResourcesIfMissing("config.yml")
        greetingTemplate = YamlFiles(this)
            .loadDataFile("config.yml")
            .getString("greeting", Greetings.DEFAULT_TEMPLATE)
            ?: Greetings.DEFAULT_TEMPLATE

        registerCommand("hello", CommandExecutor { sender, _, _, args ->
            val name = args.firstOrNull() ?: sender.name
            messager().send(sender, text().color(Greetings.render(greetingTemplate, name)))
            true
        })
    }
}
