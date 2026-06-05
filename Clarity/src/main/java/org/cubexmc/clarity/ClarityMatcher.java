package org.cubexmc.clarity;

import java.util.List;
import java.util.Locale;

/**
 * 纯匹配逻辑(无 Bukkit 依赖,便于单测)。
 *
 * <p>黑名单驱动:只有显式点名的命名空间/id/类型才会命中。属性 modifier 永远不会命中
 * {@code minecraft} 命名空间,避免误删原版 modifier。</p>
 */
public final class ClarityMatcher {

    /** 超过此 tick 数视为"无限/超长"(无限药水效果常用 Integer.MAX_VALUE)。约等于 13.9 小时。 */
    public static final long INFINITE_THRESHOLD_TICKS = 1_000_000L;

    private ClarityMatcher() {
    }

    /**
     * 某个 attribute modifier 是否命中黑名单。
     *
     * @param fullKey   modifier 的完整 NamespacedKey 字符串,如 {@code adapt:walk_speed}
     * @param namespace modifier 的命名空间,如 {@code adapt}
     * @param blacklist 配置的命名空间/id 列表
     * @return 命中则 true;{@code minecraft} 命名空间硬性返回 false
     */
    public static boolean matchesModifier(String fullKey, String namespace, List<String> blacklist) {
        return matchesNamespacedKey(fullKey, namespace, blacklist, true);
    }

    /**
     * 命名空间/id 黑名单匹配。支持命名空间、完整 key、完整 key 前缀。
     *
     * @param protectMinecraft true 时永不命中 {@code minecraft} 命名空间
     */
    public static boolean matchesNamespacedKey(String fullKey, String namespace, List<String> blacklist,
                                               boolean protectMinecraft) {
        if (fullKey == null || namespace == null || blacklist == null) {
            return false;
        }
        if (protectMinecraft && namespace.equalsIgnoreCase("minecraft")) {
            return false;
        }
        String full = fullKey.toLowerCase(Locale.ROOT);
        String ns = namespace.toLowerCase(Locale.ROOT);
        for (String raw : blacklist) {
            if (raw == null) {
                continue;
            }
            String pat = raw.trim().toLowerCase(Locale.ROOT);
            if (pat.isEmpty()) {
                continue;
            }
            if (ns.equals(pat) || full.equals(pat) || full.startsWith(pat)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 某个药水效果类型是否命中列表(按 path 或完整 key,大小写不敏感)。
     *
     * @param fullKey 完整 key,如 {@code minecraft:speed}
     * @param path    路径部分,如 {@code speed}
     * @param types   配置的类型列表
     */
    public static boolean matchesEffect(String fullKey, String path, List<String> types) {
        if (types == null) {
            return false;
        }
        String f = fullKey == null ? "" : fullKey.toLowerCase(Locale.ROOT);
        String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
        for (String raw : types) {
            if (raw == null) {
                continue;
            }
            String t = raw.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) {
                continue;
            }
            if (p.equals(t) || f.equals(t)) {
                return true;
            }
        }
        return false;
    }

    /** 持续时间是否为"无限/超长"(负数表示原版的 INFINITE_DURATION,或超过阈值的极大值)。 */
    public static boolean isInfiniteDuration(int durationTicks) {
        return durationTicks < 0 || durationTicks > INFINITE_THRESHOLD_TICKS;
    }

    /** LevelTools 在 lore 行前写入 "§§";反色显示时可稳定识别为 "&&" 前缀。 */
    public static boolean isLevelToolsLoreLine(String text) {
        if (text == null) {
            return false;
        }
        return text.replace('\u00A7', '&').startsWith("&&");
    }
}
