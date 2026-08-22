package org.cubexmc.cubexlib

import org.cubexmc.core.CubexPlugin

/**
 * CubeXLib —— 外置模式插件的运行时。
 *
 * 本插件**不提供任何玩法**。它的职责是以**原包名**把 `cubex-*` 共享模块与 Kotlin stdlib
 * 放到服务器上，供 `depend: [CubeXLib]` 的插件直接按类型调用（见 `PLAN.md` §7.1）。
 *
 * 内嵌模式的插件不需要它：它们把模块 shade + relocate 进了自己的 jar，
 * 缺席 CubeXLib 时照常单独运行。
 *
 * 后续的有状态共享服务（effect / quest / economy，`PLAN.md` §7.5）会挂在这里——
 * 它是单实例，而这正是有状态能力唯一正确的落点。
 */
class CubeXLibPlugin : CubexPlugin() {

    override fun enablePlugin() {
        log().info("CubeXLib ${description.version} 就绪：cubex-* 共享模块以原包名提供给外置模式插件")
    }
}
