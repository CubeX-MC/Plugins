object CubexRelocations {
    private val pluginIds = mapOf(
        "BookLite" to "booklite",
        "FAWEReplacer" to "fawereplace",
        "MountLicense" to "mountlicense",
        "Contract" to "contract",
        "EcoBalancer" to "ecobalancer",
        "RuleGems" to "rulegems",
        "Metro" to "metro",
        "Railway" to "railway",
        "Clarity" to "clarity",
        "Reputations" to "reputations",
        "Regions" to "regions",
        "StateCharge" to "statecharge",
        // 运行时 lib 插件(PLAN §7.1)。它不 relocate kotlin/cubex-*,但仍会 relocate FoliaLib。
        "CubeXLib" to "cubexlib",
        // cookbook 范例(PLAN §7.3)。外置模式不 relocate 任何东西,登记只是为了让 jarGate 能取到命名空间。
        "hello-external" to "cookbook.helloexternal",
        "daily-reward" to "cookbook.dailyreward",
        "soulbound-tool" to "cookbook.soulboundtool",
        "rename-menu" to "cookbook.renamemenu",
        "welcome-back" to "cookbook.welcomeback",
    )

    fun libsNamespace(projectName: String): String =
        "org.cubexmc.${pluginIds.getValue(projectName)}.libs"
}
