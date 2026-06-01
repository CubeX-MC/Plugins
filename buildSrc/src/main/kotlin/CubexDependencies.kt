object CubexDeps {
    // —— 内置/shade 进 jar 的库 ——
    const val sqliteJdbc = "org.xerial:sqlite-jdbc:${CubexVersions.sqliteJdbc}"
    const val gson = "com.google.code.gson:gson:${CubexVersions.gson}"
    const val hikariCP = "com.zaxxer:HikariCP:${CubexVersions.hikariCP}"

    // —— provided / compileOnly 的服务器与第三方 API(version 由各插件传入,保留其下限)——
    fun spigotApi(v: String) = "org.spigotmc:spigot-api:$v"
    fun paperApi(v: String) = "io.papermc.paper:paper-api:$v"
    const val vault = "com.github.MilkBowl:VaultAPI:1.7.1"
    const val placeholderApi = "me.clip:placeholderapi:2.11.6"

    // —— 测试 ——
    const val junitJupiter = "org.junit.jupiter:junit-jupiter:${CubexVersions.junit}"
    const val mockitoCore = "org.mockito:mockito-core:${CubexVersions.mockito}"
}
