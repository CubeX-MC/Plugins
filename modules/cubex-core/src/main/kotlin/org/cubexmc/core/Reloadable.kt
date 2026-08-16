package org.cubexmc.core

fun interface Reloadable {
    @Throws(Exception::class)
    fun reload()
}
