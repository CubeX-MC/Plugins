# CubeX Cookbook

每篇是一个**能编译、有单测、随 `gradlew build` 一起跑**的最小插件。
过期会立刻变红——这是它比提示词模板和纯文档更可靠的原因（见 [`PLAN.md`](../PLAN.md) §7.3）。

`PLAN.md` §3.2 (d) 已把"配一篇范例"写成新增共享能力的准入条件：
**没有范例的能力，对 AI agent 等于不存在。**

## 篇目

| # | 目录 | 模式 | 演示 | 用到的能力 |
|---|---|---|---|---|
| 01 | [`hello-external/`](hello-external) | 外置 | 最小插件：配置文件 + 一条命令 + 颜色渲染 | `CubexPlugin` · `YamlFiles` · `text()` |
| 02 | [`welcome-back/`](welcome-back) | **内嵌** | 进服欢迎语；留一篇内嵌的，免得只有外置模式被验证到 | `onEvent` |
| 03 | [`daily-reward/`](daily-reward) | 外置 | 每日签到：进服自动发奖，24 小时冷却 | `onEvent` · `Cooldown` |
| 04 | [`soulbound-tool/`](soulbound-tool) | 外置 | 灵魂绑定：给手持物打标记，再扫全身找出别人的绑定物 | `CubexPdc` · `PlayerItems` |
| 05 | [`rename-menu/`](rename-menu) | 外置 | 改名菜单：箱子界面 → 聊天输入 → 改手持物名字 | `Menu` · `fillEmpty` · `ChatInputState` · `ModernChatBridge` |

每篇的**纯逻辑都抽成了不依赖 Bukkit 的对象**（`DailyReward` / `SoulboundReport` / `NameRules`），
单测直接打它，不需要起服务器 —— 这是 cookbook 的写法示范，也是它们不会腐烂的原因。
| 02 | [`welcome-back/`](welcome-back) | **内嵌模式**（默认）+ `onEvent` 自动绑定监听器 | `cubex-core` |

## 写一篇新范例

1. 30–50 行主体，**纯逻辑抽成不依赖 Bukkit 的对象**（见 `Greetings`），单测直接打它，不 mock 服务器。
2. 在 [`settings.gradle.kts`](../settings.gradle.kts) 与
   [`CubexRelocations.kt`](../buildSrc/src/main/kotlin/CubexRelocations.kt) 登记子项目。
3. 更新上面的篇目表。

## 注意

- **`hello-external` 是全仓唯一的外置模式消费方**，`jarGate` 的 `EXTERNAL` 分支靠它保持有效。
  删掉它，外置模式的打包与 `depend` 注入就没有任何东西在验证了。
- 范例**不进镜像同步**（[`mirror.yml`](../.github/workflows/mirror.yml) 无对应 repo），
  也不对外发布。要对外发的插件必须是内嵌模式，见 [`AGENTS.md`](../AGENTS.md) 硬约束。
