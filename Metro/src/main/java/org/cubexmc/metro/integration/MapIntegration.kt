package org.cubexmc.metro.integration

interface MapIntegration {
    fun isAvailable(): Boolean = true

    fun enable()

    fun disable()

    fun refresh()

    fun isEnabled(): Boolean
}
