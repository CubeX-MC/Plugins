# Reputations 实服测试

## 已验证启动基线（2026-08-24）

测试环境：Paper `git-Paper-196`（Minecraft 1.20.1）、Java 21、Reputations 1.0.0；
可选集成轮次使用 PlaceholderAPI 2.11.6。

- [x] 不安装 PlaceholderAPI：Reputations 独立加载、注册 `ReputationService` 并正常停服，无缺类错误。
- [x] 安装 PlaceholderAPI：日志出现 `Successfully registered internal expansion: reputations [1.0.0]`。
- [x] `/papi list` 显示 1 个活动 hook：`reputations`；`plugins` 中两插件均正常启用。
- [x] 两轮都通过控制台 `stop` 正常关闭，Reputations disable 路径无异常。

以上只证明启动、可选依赖降级和 expansion 注册，不替代下面的真人玩法验收。

## 准备

1. 备份测试服和 `plugins/Reputations`，只部署最终 shadow JAR。
2. 准备普通玩家 Alice、Bob、管理员，以及会注册字段的 Contract；记录各自 UUID。
3. 第一轮不装 PlaceholderAPI，第二轮安装与生产一致的 PlaceholderAPI 版本。
4. 保存测试前的 `reputation-data.yml`、服务端版本和完整启动日志。

## 命令、权限与 GUI

1. Alice 仅有 `reputation.use`：验证 `/rep`、`/rep Bob`、`/rep fields` 和
   `/rep top Contract:completed`；set/add/reset/reload 必须被拒绝。
2. 管理员依次执行 set、正负 add、reset；无效玩家、字段、数值和页码都应返回明确错误且不改数据。
3. 验证 GUI 字段图标、说明、默认值和目标玩家名；超过 54 个字段时记录当前只显示前 54 个的既有边界。
4. 给两名玩家设置同值，确认共享名次；对 `higherIsBetter=false` 字段确认低值排前。
5. reset 后该玩家应退出该字段排行榜；从未写入、只读取默认值的玩家不得进入排行榜。

## PlaceholderAPI

1. `/papi list` 必须包含 `reputations`。
2. 对 Alice 验证 `%reputations_value_Contract:completed%` 与
   `%reputations_rank_Contract:completed%`。
3. 验证 `%reputations_top_name_1_Contract:completed%` 和
   `%reputations_top_value_1_Contract:completed%` 与 `/rep top` 一致。
4. 测试字段 key 大小写、越界榜位、未知字段和没有玩家上下文；返回值须符合 README。
5. 卸载或禁用 PlaceholderAPI 后重启，Reputations 仍须完整运行。

## 事件与跨插件边界

1. 用测试消费方从 Reputations 的 ClassLoader 解析 `ReputationChangeEvent`，不要 shade 或直接依赖 API。
2. 分别触发 SET、ADD、RESET，核对 UUID、field key、previous/new、delta 和 change type。
3. 设为相同值、add 0、已是默认值时再次 reset，均不得广播“数值变化”事件。
4. 从异步测试任务调用 add；事件必须标记为 asynchronous，监听方回到自己的调度域后再访问 Bukkit。

## 持久化与恢复

1. 正常停服重启，核对数值、缓存玩家名、GUI 与排行榜完全一致。
2. 卸载 Contract 后重启：字段不再展示，但 `reputation-data.yml` 中旧值保留；装回后恢复展示。
3. 在 flush 前后分别检查文件，确认 `storage.flush-interval-seconds` 生效。
4. 保留备份后损坏 YAML，记录实际加载行为；当前没有自动备份恢复能力，任何整文件数据丢失都阻断首发。

记录每一步的命令、玩家 UUID、前后 YAML、事件载荷和日志。尚未完成的最低版本、真人权限、GUI、
真实字段数据、异步事件及异常恢复路径不得标为已验证。
