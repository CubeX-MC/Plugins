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

// —— 插件子项目(目录原名,原地成为子项目)——
// 第0阶段先只纳入 BookLite;第1阶段把其余插件逐个加进这个列表。
listOf("BookLite", "FAWEReplacer", "MountLicense", "Contract", "EcoBalancer", "RuleGems", "Metro", "Railway", "Clarity", "Reputations", "Regions", "StateCharge").forEach {
    include(":$it"); project(":$it").projectDir = file(it)
}
