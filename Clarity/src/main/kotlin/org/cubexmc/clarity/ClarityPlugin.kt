package org.cubexmc.clarity

import org.cubexmc.clarity.metrics.Metrics
import org.cubexmc.core.CubexPlugin

/**
 * Clarity — 清理遗留 attribute modifier / 无限药水效果的工具插件。
 *
 * 核心是安全的手动命令(scan/purge,支持 @ 选择器);可选的进服自动清扫走配置黑名单,
 * 默认关闭、默认 dry-run。走 Bukkit Attribute API(服务端负责持久化),不解析二进制 NBT。
 * 详见 [ClarityService]。
 */
class ClarityPlugin : CubexPlugin() {
    @Volatile
    private lateinit var currentConfig: ClarityConfig
    private lateinit var service: ClarityService
    private var metrics: Metrics? = null

    override fun enablePlugin() {
        saveDefaultConfig()
        currentConfig = ClarityConfig.load(config)
        service = ClarityService(this)

        val executor = ClarityCommand(this, service)
        if (!registerCommand("clarity", executor)) {
            logger.warning("Command 'clarity' missing from plugin.yml — commands unavailable.")
        }

        registerListener(JoinListener(this, service))
        metrics = Metrics(this, BSTATS_PLUGIN_ID)

        logger.info(
            "Clarity enabled. auto-clean-on-join=${currentConfig.autoCleanOnJoin()} " +
                "dry-run=${currentConfig.dryRun()}",
        )
    }

    /** 当前配置快照(reload 后会被替换)。 */
    fun config(): ClarityConfig = currentConfig

    fun reloadClarityConfig() {
        reloadConfig()
        currentConfig = ClarityConfig.load(config)
    }

    private companion object {
        const val BSTATS_PLUGIN_ID = 31800
    }
}
