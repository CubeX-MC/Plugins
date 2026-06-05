package org.cubexmc.clarity;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.cubexmc.clarity.metrics.Metrics;

/**
 * Clarity — 清理遗留 attribute modifier / 无限药水效果的工具插件。
 *
 * <p>核心是安全的手动命令(scan/purge,支持 @ 选择器);可选的进服自动清扫走配置黑名单,
 * 默认关闭、默认 dry-run。走 Bukkit Attribute API(服务端负责持久化),不解析二进制 NBT。
 * 详见 {@link ClarityService}。</p>
 */
public final class ClarityPlugin extends JavaPlugin {

    private static final int BSTATS_PLUGIN_ID = 31800;

    private volatile ClarityConfig config;
    private ClarityService service;
    private Metrics metrics;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = ClarityConfig.load(getConfig());
        this.service = new ClarityService(this);

        PluginCommand cmd = getCommand("clarity");
        if (cmd != null) {
            ClarityCommand executor = new ClarityCommand(this, service);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        } else {
            getLogger().warning("Command 'clarity' missing from plugin.yml — commands unavailable.");
        }

        getServer().getPluginManager().registerEvents(new JoinListener(this, service), this);

        this.metrics = new Metrics(this, BSTATS_PLUGIN_ID);

        getLogger().info("Clarity enabled. auto-clean-on-join=" + config.autoCleanOnJoin()
                + " dry-run=" + config.dryRun());
    }

    /** 当前配置快照(reload 后会被替换)。 */
    public ClarityConfig config() {
        return config;
    }

    public void reloadClarityConfig() {
        reloadConfig();
        this.config = ClarityConfig.load(getConfig());
    }
}
