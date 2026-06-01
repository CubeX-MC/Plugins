package org.cubexmc.metro.bedrock;

/**
 * Delay tunings used by Bedrock-aware flows. Centralized so future fixes can adjust
 * a single place instead of editing scattered call sites.
 */
final class BedrockTimings {

    static final long JAVA_MOUNTED_TELEPORT_DELAY_TICKS = 1L;
    static final long JAVA_REMOUNT_DELAY_TICKS = 2L;
    static final long BEDROCK_MOUNTED_TELEPORT_DELAY_TICKS = 5L;
    static final long BEDROCK_REMOUNT_DELAY_TICKS = 8L;

    private BedrockTimings() {
    }
}
