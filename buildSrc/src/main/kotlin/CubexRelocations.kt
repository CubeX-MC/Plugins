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
    )

    fun libsNamespace(projectName: String): String =
        "org.cubexmc.${pluginIds.getValue(projectName)}.libs"
}
