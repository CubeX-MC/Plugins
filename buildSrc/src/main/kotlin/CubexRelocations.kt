object CubexRelocations {
    private val pluginIds = mapOf(
        "BookLite" to "booklite",
        "FAWEReplacer" to "fawereplace",
        "MountLicense" to "mountlicense",
        "Contracts" to "contract",
        "EcoBalancer" to "ecobalancer",
        "RuleGems" to "rulegems",
        "Metro" to "metro",
        "Railway" to "railway",
        "Clarity" to "clarity",
    )

    fun libsNamespace(projectName: String): String =
        "org.cubexmc.${pluginIds.getValue(projectName)}.libs"
}
