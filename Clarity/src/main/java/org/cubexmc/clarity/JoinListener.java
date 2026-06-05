package org.cubexmc.clarity;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** 玩家进服时按配置黑名单自动清扫(默认关闭;延迟执行给其它插件留时间)。 */
public final class JoinListener implements Listener {

    private final ClarityPlugin plugin;
    private final ClarityService service;

    public JoinListener(ClarityPlugin plugin, ClarityService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        ClarityConfig cfg = plugin.config();
        if (!cfg.autoCleanOnJoin()) {
            return;
        }
        service.sweep(null, event.getPlayer(), cfg.joinDelayTicks());
    }
}
