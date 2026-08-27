# Reputations

![](https://bstats.org/signatures/bukkit/Reputations.svg)

跨插件共享的玩家信誉服务：各插件注册自己的信誉字段并更新数值，玩家在一个界面里看到全部。

## 定位

服务器上多个插件各自记一套"信誉"时，玩家要翻好几个命令才能看全，而且没有任何一处能横向比较。
Reputations 把**存储和展示**收到一处：它自己不定义任何玩法字段，只提供
「注册字段 → 读写数值 → 统一展示」的底座，字段内容由各业务插件决定。

采用 **Vault 模式**：通过 Bukkit `ServicesManager` 暴露 `ReputationService` 接口，
消费方按可选服务连接，Reputations 不在场时消费方降级运行、不会崩。

**不做什么**（避免误装）：

- 不自带任何信誉玩法。没有插件注册字段时，它是个空壳。
- 不替业务插件做判定：字段涨跌的规则归注册方，Reputations 只存和显示。
- 不强制成为依赖：接入方必须能在它缺席时完整运行。

## 功能特性

- `ReputationService`（Bukkit ServicesManager）：`registerField` / `field` / `value` / `add` / `reset`。
- 字段键为 `namespace:id`（两段都不允许含 `.` 或 `:`），例如 `Contract:completed`。
- 字段自带展示元数据：显示名、描述、GUI 图标（Bukkit Material 名）、默认值、`higherIsBetter` 展示提示。
- 字段注册**不持久化**（各插件每次 enable 时重新注册），但每个玩家的**数值持久化**跨重启保留。
- 分页 GUI 查看某玩家的全部信誉字段。
- 按字段查看分页排行榜；`higherIsBetter` 决定数值升序或降序，同值共享名次。
- 管理命令：按字段 set / add / reset，以及 reload。
- 数值实际变化后广播 `ReputationChangeEvent`，供其他插件以 Bukkit 事件方式观察。
- 可选 PlaceholderAPI 占位符：玩家数值/名次，以及任意字段的全服榜位。
- 公开 API 面是 Java（`org.cubexmc.reputations.api`），便于任意 JVM 语言接入。

## 运行要求

| 项 | 要求 |
|---|---|
| 服务端 | Spigot / Paper 1.18+ |
| Java | 17 |
| 必需依赖 | 无 |
| 可选依赖 | PlaceholderAPI 2.11+ |

## 安装

1. 把 `reputations-<version>.jar` 放进服务器 `plugins/`。
2. 启动服务器生成默认配置。
3. 装上会注册字段的业务插件（例如 Contract），字段才会出现在界面里。

> 部署用的是 `build/libs/reputations-<version>.jar`；同目录的 `*-plain.jar` **不要**部署。

## 命令

别名：`/rep`、`/reps`

| 命令 | 权限 | 说明 |
|---|---|---|
| `/reputation [玩家]` | `reputation.use` | 查看自己或指定玩家的信誉界面 |
| `/reputation fields` | `reputation.use` | 列出当前已注册的字段 |
| `/reputation top <字段key> [页码]` | `reputation.use` | 查看字段排行榜，每页 10 人 |
| `/reputation set <玩家> <字段key> <数值>` | `reputation.admin` | 直接设置某字段的值 |
| `/reputation add <玩家> <字段key> <数值>` | `reputation.admin` | 增减某字段的值（可为负） |
| `/reputation reset <玩家> <字段key>` | `reputation.admin` | 清除存储值，读回字段默认值 |
| `/reputation reload` | `reputation.admin` | 重新加载配置与语言 |

## 权限

| 权限 | 默认 | 说明 |
|---|---|---|
| `reputation.use` | true | 查看信誉界面、字段与排行榜 |
| `reputation.admin` | op | set / add / reset / reload |

## 配置

| 键 | 默认 | 说明 |
|---|---|---|
| `gui.title` | `玩家信誉` | 信誉界面标题前缀，后面会拼上被查看玩家的名字 |

### PlaceholderAPI

identifier 为 `reputations`；字段 key 不区分大小写。以下示例使用 Contract 注册的
`Contract:completed`：

| 占位符 | 返回值 |
|---|---|
| `%reputations_value_Contract:completed%` | 当前玩家的字段值；没有玩家上下文时为 `0` |
| `%reputations_rank_Contract:completed%` | 当前玩家名次；尚无该字段持久化值时为 `0` |
| `%reputations_top_name_1_Contract:completed%` | 第 1 个榜位的玩家名 |
| `%reputations_top_value_1_Contract:completed%` | 第 1 个榜位的数值 |

榜位只包含该字段已有持久化值的玩家。字段未注册或占位符格式不正确时交还给 PlaceholderAPI
处理；榜位超出当前人数时返回空字符串。

## 数据与安全

每个玩家的字段数值持久化保存在插件数据目录中，跨重启保留。**字段注册本身不持久化**：
某插件卸载后其字段不再出现在界面里，但已存的数值仍在，插件装回来即可重新显示。

## 给接入方（插件开发者）

消费方**不要**编译依赖或打包 Reputations 的 API：Bukkit 每插件独立 ClassLoader，
把同一个接口 shade 进两个 jar 会产生两个不同的 `Class` 身份，`ServicesManager.load` 必然失败。

读取服务的正确做法是使用仓库内的 `modules/cubex-integrations`：它从**提供方的 ClassLoader**
解析 API class 再查 `ServicesManager`，并把缺席/禁用/类型不符都归一成"可选能力不可用"。
`plugin.yml` 里只用 `softdepend: [Reputations]` 表达可选加载顺序。

`ReputationChangeEvent` 包含玩家 UUID、字段 key、前后值、delta 与变动类型。若更新来自异步线程，
`event.isAsynchronous()` 为 `true`，监听方必须回到自己的调度域后再触碰 Bukkit 状态。监听方同样不能
shade 事件类；应从 Reputations 的 ClassLoader 解析事件类型后，用 Bukkit 的动态事件注册入口连接。

参考实现见 Contract 的 Reputations 服务桥；共享模块用法见仓库根 [`MODULES.md`](../MODULES.md)。

## 构建

```powershell
.\gradlew.bat :Reputations:build      # 编译 + 测试 + 部署 jar
.\gradlew.bat :Reputations:test       # 只跑测试
.\gradlew.bat :Reputations:jarGate    # 部署 jar 门禁
.\gradlew.bat :Reputations:runServer  # Paper 1.20.1 + PlaceholderAPI 2.11.6 开发服
```

Windows 必须用 PowerShell 跑 `.\gradlew.bat`（仓库路径含空格）。
产物在 `Reputations/build/libs/`；`*-plain.jar` **不要**部署。

## 已知边界

- 尚未发布首个正式版本；当前尚无独立镜像 repo。
- 目前只有 Contract 一个字段提供方；排行榜不会扫描服务器的全部离线玩家，只展示该字段已有持久化值的记录。
- 不提供跨字段的加权聚合或等级(tier)推导——权重口径属于未定的玩法决策，刻意留白。

## 相关文档

- 待办与路线：仓库根 [`PLAN.md`](../PLAN.md)
- 可选连接与共享模块：仓库根 [`MODULES.md`](../MODULES.md)
- 实服验收：[`REAL_SERVER_TEST.md`](REAL_SERVER_TEST.md)
- 发布检查：[`docs/release-checklist.md`](docs/release-checklist.md)
