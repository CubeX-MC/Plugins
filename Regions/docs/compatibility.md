# 兼容性

## 支持矩阵

| 组件 | 状态 | 说明 |
| --- | --- | --- |
| Java 21 | 必需 | 构建与运行基线 |
| Paper 1.21.11 | 支持 | 默认开发和 CI API 基线 |
| Folia | 支持，需真人回归 | `folia-supported: true`，实体操作使用统一调度器 |
| Lands | 可选 | 仅在插件已启用且配置允许时提供 Source/工会能力 |
| RuleGems | 可选治理集成 | 日常管理仍由 Regions 权限与来源所有权共同判定 |

## 原生能力

`scale` 依赖服务端存在对应原生 Attribute；默认 `require-native-support: true`。Potion、Sound、Material 与 World 名称在可获得 Bukkit registry/server 时于发布前校验，在纯单元测试环境使用结构校验。

## 升级与回退

升级前备份 `plugins/Regions`。配置含 `config-version`；region 存储、revision、审计、Effect lease 和装备 escrow 都应随备份保留。回退 JAR 前确认旧版本是否识别新 schema；不确定时先在测试服用备份副本验证，禁止直接删除 escrow 文件来“解决”恢复问题。

Lands 或 RuleGems reload 后运行 `/regions validate` 并观察 Source 可用性。外部依赖短暂不可用不应使 Regions 丢失 revision；是否阻止启动由 `required-for-startup` 决定。

