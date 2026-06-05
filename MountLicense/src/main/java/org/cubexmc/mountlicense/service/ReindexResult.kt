package org.cubexmc.mountlicense.service

class ReindexResult(
    private val scanned: Int,
    private val recovered: Int,
) {
    fun scanned(): Int = scanned

    fun recovered(): Int = recovered
}
