rootProject.name = "CubeX-Plugins"
gradle.startParameter.isParallelProjectExecutionEnabled = true

// —— 共享模块(第2阶段陆续解除注释)——
include(":modules:cubex-core"); project(":modules:cubex-core").projectDir = file("modules/cubex-core")
include(":modules:cubex-scheduler"); project(":modules:cubex-scheduler").projectDir = file("modules/cubex-scheduler")
include(":modules:cubex-config"); project(":modules:cubex-config").projectDir = file("modules/cubex-config")
include(":modules:cubex-i18n"); project(":modules:cubex-i18n").projectDir = file("modules/cubex-i18n")
include(":modules:cubex-integrations"); project(":modules:cubex-integrations").projectDir = file("modules/cubex-integrations")
include(":modules:cubex-spatial"); project(":modules:cubex-spatial").projectDir = file("modules/cubex-spatial")
include(":modules:cubex-gui"); project(":modules:cubex-gui").projectDir = file("modules/cubex-gui")
include(":modules:cubex-database"); project(":modules:cubex-database").projectDir = file("modules/cubex-database")
include(":modules:cubex-command"); project(":modules:cubex-command").projectDir = file("modules/cubex-command")
include(":modules:cubex-economy"); project(":modules:cubex-economy").projectDir = file("modules/cubex-economy")

// —— 运行时 lib 插件(PLAN §7.1):为外置模式插件提供 cubex-* ——
include(":CubeXLib"); project(":CubeXLib").projectDir = file("CubeXLib")

// —— cookbook 范例(PLAN §7.3):可编译、带单测、随 build 一起跑,过期立刻变红 ——
// hello-external 同时是**外置模式的唯一消费方**,jarGate 的 EXTERNAL 分支靠它保持有效。
include(":cookbook:hello-external"); project(":cookbook:hello-external").projectDir = file("cookbook/hello-external")
include(":cookbook:daily-reward"); project(":cookbook:daily-reward").projectDir = file("cookbook/daily-reward")
include(":cookbook:soulbound-tool"); project(":cookbook:soulbound-tool").projectDir = file("cookbook/soulbound-tool")
include(":cookbook:rename-menu"); project(":cookbook:rename-menu").projectDir = file("cookbook/rename-menu")
include(":cookbook:welcome-back"); project(":cookbook:welcome-back").projectDir = file("cookbook/welcome-back")

// —— 插件子项目(目录原名,原地成为子项目)——
// 第0阶段先只纳入 BookLite;第1阶段把其余插件逐个加进这个列表。
listOf("BookLite", "FAWEReplacer", "MountLicense", "Contract", "EcoBalancer", "RuleGems", "Metro", "Railway", "Clarity", "Reputations", "Regions", "StateCharge").forEach {
    include(":$it"); project(":$it").projectDir = file(it)
}
