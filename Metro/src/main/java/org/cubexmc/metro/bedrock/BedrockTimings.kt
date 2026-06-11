package org.cubexmc.metro.bedrock

/**
 * Delay tunings used by Bedrock-aware flows. Centralized so future fixes can adjust
 * a single place instead of editing scattered call sites.
 */
object BedrockTimings {
    const val JAVA_MOUNTED_TELEPORT_DELAY_TICKS: Long = 1L
    const val JAVA_REMOUNT_DELAY_TICKS: Long = 2L
    const val BEDROCK_MOUNTED_TELEPORT_DELAY_TICKS: Long = 5L
    const val BEDROCK_REMOUNT_DELAY_TICKS: Long = 8L
}
