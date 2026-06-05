package org.cubexmc.clarity;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClarityMatcherTest {

    @Test
    void matchesByNamespace() {
        assertTrue(ClarityMatcher.matchesModifier("adapt:walk_speed", "adapt", List.of("adapt")));
    }

    @Test
    void matchesByExactFullKey() {
        assertTrue(ClarityMatcher.matchesModifier("adapt:walk_speed", "adapt", List.of("adapt:walk_speed")));
    }

    @Test
    void matchesByPrefix() {
        assertTrue(ClarityMatcher.matchesModifier("adapt:walk_speed", "adapt", List.of("adapt:walk")));
    }

    @Test
    void isCaseInsensitive() {
        assertTrue(ClarityMatcher.matchesModifier("Adapt:Walk_Speed", "Adapt", List.of("ADAPT")));
    }

    @Test
    void neverMatchesMinecraftNamespace() {
        // 硬护栏:即便黑名单显式写了 minecraft,也绝不命中原版 modifier。
        assertFalse(ClarityMatcher.matchesModifier("minecraft:sprinting", "minecraft", List.of("minecraft")));
        assertFalse(ClarityMatcher.matchesModifier("minecraft:sprinting", "minecraft", List.of("minecraft:sprinting")));
    }

    @Test
    void genericNamespacedKeyCanMatchMinecraftWhenAllowed() {
        assertTrue(ClarityMatcher.matchesNamespacedKey(
                "minecraft:custom_data", "minecraft", List.of("minecraft:custom_data"), false));
        assertFalse(ClarityMatcher.matchesNamespacedKey(
                "minecraft:custom_data", "minecraft", List.of("minecraft:custom_data"), true));
    }

    @Test
    void doesNotMatchUnrelatedNamespace() {
        assertFalse(ClarityMatcher.matchesModifier("otherplugin:boost", "otherplugin", List.of("adapt")));
    }

    @Test
    void ignoresBlankAndNullBlacklistEntries() {
        java.util.List<String> blacklist = new java.util.ArrayList<>();
        blacklist.add("  ");
        blacklist.add(null);
        blacklist.add("adapt");
        assertTrue(ClarityMatcher.matchesModifier("adapt:x", "adapt", blacklist));
        assertFalse(ClarityMatcher.matchesModifier("zzz:x", "zzz", Collections.singletonList("  ")));
    }

    @Test
    void effectMatchesByPathOrFullKey() {
        assertTrue(ClarityMatcher.matchesEffect("minecraft:speed", "speed", List.of("SPEED")));
        assertTrue(ClarityMatcher.matchesEffect("minecraft:speed", "speed", List.of("minecraft:speed")));
        assertFalse(ClarityMatcher.matchesEffect("minecraft:speed", "speed", List.of("night_vision")));
    }

    @Test
    void infiniteDurationDetection() {
        assertTrue(ClarityMatcher.isInfiniteDuration(-1));               // INFINITE_DURATION
        assertTrue(ClarityMatcher.isInfiniteDuration(Integer.MAX_VALUE)); // RuleGems 风格无限
        assertFalse(ClarityMatcher.isInfiniteDuration(600));             // 30s 药水
        assertFalse(ClarityMatcher.isInfiniteDuration(0));
    }

    @Test
    void detectsLevelToolsLorePrefix() {
        assertTrue(ClarityMatcher.isLevelToolsLoreLine("\u00A7\u00A7\u00A7eLevel: \u00A761"));
        assertTrue(ClarityMatcher.isLevelToolsLoreLine("&&&eLevel: &61"));
        assertFalse(ClarityMatcher.isLevelToolsLoreLine("\u00A7eLevel: \u00A761"));
        assertFalse(ClarityMatcher.isLevelToolsLoreLine(null));
    }
}
