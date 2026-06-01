package org.cubexmc.ecobalancer.utils;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Vault经济相关工具类
 */
public class VaultUtils {
    private static Economy economy = null;
    
    /**
     * 初始化Vault经济系统
     * @param plugin 插件实例
     * @return 是否初始化成功
     */
    public static boolean setupEconomy(JavaPlugin plugin) {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().severe("未找到Vault插件，经济系统无法初始化");
            return false;
        }
        
        RegisteredServiceProvider<Economy> rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            plugin.getLogger().severe("未找到Vault经济服务提供者");
            return false;
        }
        
        economy = rsp.getProvider();
        return economy != null;
    }
    
    /**
     * 获取Economy实例
     * @return Economy实例
     */
    public static Economy getEconomy() {
        return economy;
    }
    
    /**
     * 检查玩家是否有账户
     * @param player 玩家
     * @return 是否有账户
     */
    public static boolean hasAccount(OfflinePlayer player) {
        if (economy == null) {
            throw new IllegalStateException("Vault经济系统未初始化");
        }
        return economy.hasAccount(player);
    }
    
    /**
     * 获取玩家余额
     * @param player 玩家
     * @return 玩家余额
     */
    public static double getBalance(OfflinePlayer player) {
        if (economy == null) {
            throw new IllegalStateException("Vault经济系统未初始化");
        }
        return economy.getBalance(player);
    }
    
    /**
     * 存款到玩家账户
     * @param player 玩家
     * @param amount 金额
     * @return 操作是否成功
     */
    public static boolean depositPlayer(OfflinePlayer player, double amount) {
        if (economy == null) {
            throw new IllegalStateException("Vault经济系统未初始化");
        }
        return economy.depositPlayer(player, amount).transactionSuccess();
    }
    
    /**
     * 从玩家账户取款
     * @param player 玩家
     * @param amount 金额
     * @return 操作是否成功
     */
    public static boolean withdrawPlayer(OfflinePlayer player, double amount) {
        if (economy == null) {
            throw new IllegalStateException("Vault经济系统未初始化");
        }
        return economy.withdrawPlayer(player, amount).transactionSuccess();
    }
    
    /**
     * 创建或获取税收账户
     * @param taxAccountName 税收账户名称
     * @return 操作是否成功
     */
    public static boolean setupTaxAccount(String taxAccountName) {
        if (economy == null) {
            throw new IllegalStateException("Vault经济系统未初始化");
        }
        
        // 检查账户是否已存在
        if (economy.hasAccount(taxAccountName)) {
            return true;  // 账户已存在，无需创建
        }
        
        // 创建账户
        boolean created = economy.createPlayerAccount(taxAccountName);
        if (!created) {
            // 如果创建失败，可能需要尝试其他方法或记录错误
            return false;
        }
        
        return true;
    }
    
    /**
     * 获取税收账户余额
     * @param taxAccountName 税收账户名称
     * @return 税收账户余额
     */
    public static double getTaxAccountBalance(String taxAccountName) {
        if (economy == null) {
            throw new IllegalStateException("Vault经济系统未初始化");
        }
        
        if (!economy.hasAccount(taxAccountName)) {
            return 0.0;  // 账户不存在
        }
        
        return economy.getBalance(taxAccountName);
    }
    
    /**
     * 向税收账户存款
     * @param taxAccountName 税收账户名称
     * @param amount 金额
     * @return 操作是否成功
     */
    public static boolean depositToTaxAccount(String taxAccountName, double amount) {
        if (economy == null) {
            throw new IllegalStateException("Vault经济系统未初始化");
        }
        
        if (!economy.hasAccount(taxAccountName)) {
            if (!setupTaxAccount(taxAccountName)) {
                return false;  // 无法创建税收账户
            }
        }
        
        return economy.depositPlayer(taxAccountName, amount).transactionSuccess();
    }
} 