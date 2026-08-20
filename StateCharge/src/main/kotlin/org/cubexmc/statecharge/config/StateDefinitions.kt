package org.cubexmc.statecharge.config

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.cubexmc.core.Reloadable
import org.cubexmc.statecharge.StateChargePlugin
import org.cubexmc.statecharge.effect.FlightEffect
import org.cubexmc.statecharge.effect.ScaleEffect
import org.cubexmc.statecharge.effect.StateEffect
import org.cubexmc.statecharge.model.StateSpec
import java.math.BigDecimal
import java.util.LinkedHashMap

/** 状态目录的只读视图:服务层只依赖它,便于测试替换。 */
interface StateCatalog {
    /** 按 id 查状态定义(含 disabled 条目,admin give / 到期清理仍需要它们)。 */
    fun byId(id: String): StateSpec?

    fun all(): List<StateSpec>

    fun purchasable(): List<StateSpec>
}

/**
 * 把 config.yml 的 `states` 段解析成 [StateSpec] 列表(Reloadable)。
 *
 * 非法条目(坏 id / 未知 effect type / 参数越界)在加载时记 severe 日志并**跳过该条目**,
 * 不阻断插件启动;`/statecharge admin reload` 后按新配置生效。
 */
class StateDefinitions(private val plugin: StateChargePlugin) : Reloadable, StateCatalog {

    private val specs = LinkedHashMap<String, StateSpec>()
    private val problems = mutableListOf<String>()

    override fun reload() {
        specs.clear()
        problems.clear()
        val section = plugin.config.getConfigurationSection("states")
        if (section == null) {
            plugin.log().warn("config.yml has no 'states' section; no states are defined.")
            return
        }
        for (id in section.getKeys(false)) {
            val stateSection = section.getConfigurationSection(id)
            if (stateSection == null) {
                problem(id, "expected a configuration section")
                continue
            }
            parse(id, stateSection)?.let { specs[id] = it }
        }
    }

    override fun byId(id: String): StateSpec? = specs[id]

    override fun all(): List<StateSpec> = specs.values.toList()

    override fun purchasable(): List<StateSpec> = specs.values.filter { it.enabled() }

    fun problems(): List<String> = problems.toList()

    private fun parse(id: String, section: ConfigurationSection): StateSpec? {
        if (!ID_PATTERN.matches(id)) {
            problem(id, "state id must match [a-z0-9_-]{1,32}")
            return null
        }
        val effect = parseEffect(id, section) ?: return null
        val unitSeconds = section.getLong("unit-seconds", DEFAULT_UNIT_SECONDS)
        if (unitSeconds < 1) {
            problem(id, "unit-seconds must be >= 1")
            return null
        }
        // max-stack-seconds 在"按开启时长计费"模型下没有意义(玩家不再囤时长),已废弃。
        val price = section.getDouble("price", 0.0)
        if (price < 0) {
            problem(id, "price must be >= 0")
            return null
        }
        val permission = section.getString("permission", "")?.takeIf { it.isNotBlank() }
        // 互斥组缺省按效果类型派生(scale→scale、fly→fly),显式空串关闭互斥。
        val conflictGroup = if (section.isSet("conflict-group")) {
            section.getString("conflict-group")?.takeIf { it.isNotBlank() }
        } else {
            defaultConflictGroup(effect)
        }
        val display = section.getString("display", id)?.takeIf { it.isNotBlank() } ?: id
        // 图标只影响交易页展示,写错不该让整个状态加载失败 —— 回退到默认值并记一条 problem。
        val iconName = section.getString("icon", "")?.takeIf { it.isNotBlank() }
        val icon = iconName?.let { Material.matchMaterial(it) }
        if (iconName != null && icon == null) {
            problem(id, "unknown icon material '" + iconName + "', falling back to " + DEFAULT_ICON)
        }
        return StateSpec(
            id,
            display,
            BigDecimal.valueOf(price),
            unitSeconds,
            permission,
            conflictGroup,
            effect,
            section.getBoolean("enabled", true),
            icon ?: DEFAULT_ICON,
        )
    }

    private fun parseEffect(id: String, section: ConfigurationSection): StateEffect? {
        val effect = section.getConfigurationSection("effect")
        if (effect == null) {
            problem(id, "missing effect section")
            return null
        }
        return when (effect.getString("type", "")) {
            "scale" -> {
                val scale = effect.getDouble("scale", 0.0)
                if (scale < MIN_SCALE || scale > MAX_SCALE) {
                    problem(id, "effect.scale must be in [$MIN_SCALE, $MAX_SCALE]")
                    null
                } else {
                    ScaleEffect(scale)
                }
            }
            "fly" -> FlightEffect(effect.getBoolean("auto-start", true))
            else -> {
                problem(id, "unknown effect type '${effect.getString("type")}'")
                null
            }
        }
    }

    private fun defaultConflictGroup(effect: StateEffect): String? = when (effect) {
        is ScaleEffect -> "scale"
        is FlightEffect -> "fly"
        else -> null
    }

    private fun problem(id: String, message: String) {
        problems.add("$id: $message")
        plugin.log().severe("Ignoring invalid state '$id' in config.yml — $message")
    }

    private companion object {
        val ID_PATTERN = Regex("[a-z0-9_-]{1,32}")
        const val DEFAULT_UNIT_SECONDS = 1800L
        val DEFAULT_ICON: Material = Material.NAME_TAG
        const val MIN_SCALE = 0.1
        const val MAX_SCALE = 16.0
    }
}
