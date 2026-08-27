/** Classifies repository bytecode before excluding relocated third-party libraries. */
object CubexJarClasses {
    fun isUnexpectedSharedClass(path: String, namespace: String, knownPrefixes: List<String>): Boolean =
        path.endsWith(".class") && path.startsWith(namespace) && knownPrefixes.none(path::startsWith)


    fun expectedMajor(
        path: String,
        pluginMajor: Int,
        sharedMajor: Int,
        sharedPrefixes: List<String>,
        shadedPrefixes: List<String>,
    ): Int? {
        if (!path.endsWith(".class")) return null
        if (sharedPrefixes.any(path::startsWith)) return sharedMajor
        if (path.startsWith("org/cubexmc/") && shadedPrefixes.none(path::startsWith)) return pluginMajor
        return null
    }
}
