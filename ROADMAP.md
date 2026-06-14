# CubeX Plugins — Roadmap / 未来计划

> 跨插件的中长期方向。单插件的细节计划放各自目录(如 `Contracts/UI_REDESIGN_PLAN.md`)。
> 本文件只记"要做什么、为什么、注意什么",不替代具体设计文档。

## 进行中 / 近期

### R0 — Contract 接入 Reputations 并瘦身
- 已新建独立插件 **`Reputations`**(Vault 模式 · ServicesManager `ReputationService` API · bStats 31877)。
- 让 Contract 改为消费它:注册 `Contract:completed/cancelled/expired/disputed` 字段,结算/取消/争议钩子改调 `rep.add(...)`,详情读 `rep.get(...)`,删除 Contract 自带的 `ReputationStore`/`reputation.yml`/`/contract rep`。`softdepend` 缺席时优雅降级。
- 详见 `Contracts/UI_REDESIGN_PLAN.md` 的 **P4**。待定:旧 `reputation.yml` 导入 vs 从零。

## 已确认的未来计划

### 1. 全面 Kotlin 化
- 新插件直接 Kotlin(`Reputations` 已是);老插件按"小文件→大文件"逐个 `.java → .kt`;`cubex-core` 最后迁。
- 遵循既有路线与风格:`CUBEX_KOTLIN_MIGRATION_DESIGN.md`、`KOTLIN_STYLE_GUIDE.md`(含 avoid kotlin-reflect 等约束)。
- 目标:全仓统一语言,消除 Java/Kotlin 混编的样板与构建差异。

### 2. EcoBalancer 基于自定义事件的税收
- 让税收不止"定时/交易",而是可挂在**自定义游戏事件**上。示例:**死亡不掉落税**——`keepInventory` 生效、玩家死亡时按规则收税。
- 需要一个**可扩展的"触发器 → 税目"框架**:事件源(死亡/传送/开宝箱/…)+ 条件 + 税率/税额 + 去向(销毁/系统/国库),服主可配置、可增删,而非硬编码每一种税。
- 与 Reputations 类似的取向:EcoBalancer 提供"土壤",具体税种由配置/可选适配器拼装。

### 3. 完善 Reputation 系统
- 衍生**评分/等级(tier)** 与**加权聚合**(把多字段合成一个可读信誉)。
- **外部属性 provider SPI** + 软依赖适配器(如 Lands 国家/领袖);默认**只并列展示、不改分**,改分作为服主可选项(见与 Contract 讨论的公平性顾虑)。
- 排行榜、信誉变动事件广播、分页 GUI、PlaceholderAPI 占位符。
- 旧数据迁移工具(供 Contract 等首批接入方导入历史计数)。

### 4. 尽量模块化(沉淀可复用能力为 `cubex-*` 模块)
- 候选:`cubex-i18n`(已存在,推广到所有插件)、**reputation 接入 helper 模块**(封装"取 ServicesManager 服务 + 注册字段 + 降级"的样板)、`cubex-scheduler`、`cubex-config`/迁移、把 Contract 的 `Menu`/`InventoryButton` 框架抽成 **`cubex-gui`**。
- **铁律(见架构讨论)**:**有状态的共享服务 = 独立插件**(单实例持有数据,如 Reputations);**无状态的共享代码 = shade 进各 jar 的模块**(如 `cubex-core`)。别把有状态服务做成 shade 模块(各插件会各持一份、互不共享)。
- 配合 `ARCHITECTURE_PROPOSAL.md` 的 core API 边界设计,避免 god-module 与返工。

### 5. 统一所有插件指令格式
- 统一:子命令风格、权限命名(`<plugin>.<area>.<action>`)、`help`/`usage` 渲染、tab 补全、错误/成功提示、颜色规范。
- 可能沉淀为 `cubex-command` 模块或一份规范文档 + 逐插件对齐。
- 现状参差(各插件 `api-version`、用法串、提示风格不一),统一后降低玩家与维护者心智负担。

---
> 维护提示:完成一项就标 ✅ 并指向落地的 PR/设计文档;新方向先进"已确认的未来计划"再细化。
