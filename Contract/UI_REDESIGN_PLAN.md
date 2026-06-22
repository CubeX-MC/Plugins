# Contract 界面改进计划 — 合同大厅化 + Dialog/聊天输入替代铁砧

> 目标:把"工作台菜单 + 多层铁砧"的繁琐流程,改造成像 AuctionHouse 一样**以"合同大厅"为落地主界面**;文本/数字输入在**高版本走 Paper Dialog API、低版本走公屏聊天**,彻底移除 AnvilGUI。
>
> 参考实现:`reference/Auction-House`(`AuctionHouseGUI` / `InventoryGUI` / `GUIManager` / `AnvilGUIManager`)。
> 硬约束:不改任何资金/结算玩法逻辑(`ContractService` 不动),只重构 `gui` 层与输入方式。

---

## 1. 现状诊断:为什么"过于复杂"

当前入口 `ContractGui.kt`(1305 行,单类)。流程链路:

```
/ct → Hub 工作台(6 按钮) → 合同大厅 / 我的 / 收件箱 / 创建向导 / 管理
        合同大厅 → 类型筛选 → 详情 → 确认 GUI → 签名铁砧 → 执行
        创建向导 → 选类型 → 表单(每个字段开一次铁砧) → 描述(聊天) → 确认 GUI → 签名铁砧
```

具体痛点:

1. **落地多一跳。** `openHub` 是一屏"菜单的菜单"。AuctionHouse 直接落到货架大厅,Contract 却要先点一次才到大厅。
2. **导航家具重复。** 大厅同时摆了「我的合同」「公开合同」「返回工作台」三个跳转键(`ContractGui.kt:116-117`),每屏都重复 45/49/53 翻页返回,信息密度低。
3. **建合同要 6 次铁砧往返。** 标题、对方、金额、仲裁者、对方押注、期限各开一次 `AnvilGUI`,描述走聊天(`handleWizardFormClick` `ContractGui.kt:509-515`)。每次往返都要 `anvilSuppressReopen` 这套 hack(`ContractGui.kt:42, 643, 668, 802, 827…`)来兜住"关闭即重开"的竞态——这是明显的坏味道。
4. **每个动作三屏。** 例如"接单":详情 → 确认 GUI → 签名铁砧(输名字)→ 才执行(`openConfirm` → `openSignAnvil` `ContractGui.kt:289, 641`)。确认意图被拆成两屏。
5. **状态机臃肿。** `ViewType` 8 个状态、`Session` 携带 10 个字段、外加 `disputePrompts`/`descriptionPrompts`/`drafts`/`anvilSuppressReopen` 四张并行 map(`ContractGui.kt:38-42`)。中央 `onClick` → `handleXClick` → `when(slot)` 巨型分发(`ContractGui.kt:334-343`),槽位与逻辑强耦合,改一处牵一身。
6. **多一个 shaded 依赖。** `net.wesjd:anvilgui` 需要 shadow + relocate(`build.gradle.kts`),还要兼容各版本铁砧行为。

> 结论:**"复杂"主要来自(a)多一层 Hub、(b)逐字段铁砧输入、(c)确认+签名两段式、(d)集中式槽位分发。** 四者都可消除。

---

## 2. 目标体验(对标 AuctionHouse)

### 2.1 `/ct` 直接落地「合同大厅」
分页网格 = 主界面。边框区(类似 AH 的 layout)放功能键:

| 位置 | 按钮 | 行为 |
|---|---|---|
| 顶部 1-4 | 类型筛选(全部/委托/对赌/合作) | 切换筛选,原地刷新 |
| 顶部右 | 排序(截止最近/金额/最新) | 循环切换 |
| 底部 | 🔔 收件箱(带未读红点数字) | 进收件箱 |
| 底部 | 📖 我的合同 | 切到"我参与"视图(同一网格,换数据源) |
| 底部 | ➕ 创建合同 | 进创建流程(见 §2.3) |
| 底部 | 🔄 刷新 / ◀▶ 翻页 | |
| 底部(管理员) | 🛠 管理 | 进管理视图(同网格,换数据源+管理动作) |

「大厅 / 我的 / 收件箱 / 管理」**复用同一个网格 GUI 类**,只换数据源与点击目标 —— 就像 AH 用一个 `AuctionHouseGUI` + `View` 枚举覆盖多视图。Hub 工作台整屏删除。

### 2.2 详情页:一步确认
详情页直接列出可执行动作按钮(已有 `renderParticipantActions`)。点击后**一次确认**即可:
- 高版本:弹 Dialog notice(标题+后果列表+「签署执行」/「取消」两个按钮)。
- 低版本:公屏提示后果,输入 `confirm`/`yes`(或玩家名,如要保留签名语义)确认,`cancel` 取消。

取消"确认 GUI + 签名铁砧"两屏,合并为一次 Dialog/一行聊天。

### 2.3 创建合同:一张表单
- **高版本(Paper 1.21.6+)**:单个 Dialog 表单,一屏内含 类型下拉 + 标题/描述文本框 + 金额/期限数字框 + 对方/仲裁者文本框,底部「预览签署」提交。客户端原生渲染,零铁砧。
- **低版本/非 Paper**:顺序聊天向导(逐项问答,复用现有 `descriptionPrompts` 聊天机制),或保留一个**精简 GUI**(类型选择网格)+ 聊天补字段。

---

## 3. 架构改造

### 3.1 引入按钮式 GUI 框架(移植 AH 思路)
新增轻量内部框架(放 `gui/framework/`):
- `InventoryButton`(icon creator + click consumer,搬 AH 的 `InventoryButton`)。
- `MenuGui` 抽象基类(持有 `Inventory` + `Map<slot, InventoryButton>`,统一 `onClick` 派发到按钮自己的 consumer,搬 AH 的 `InventoryGUI`)。
- `MenuRegistry`(以 `Inventory` 实例为 key 路由 click/close 事件,搬 AH 的 `GUIManager`)——**替代**现在用标题字符串匹配 + 中央 `when(slot)` 的整套机制。

收益:删掉 `ViewType`/`Session.slotContracts`/巨型 `handleXClick`,每个按钮自带行为,新增/调整屏幕不再碰中央分发。

### 3.2 输入抽象 `InputService`(替代 AnvilGUI)
```
interface InputService {
    fun confirm(player, title, lines, onConfirm)            // 是/否确认
    fun promptForm(player, draftSpec, onSubmit)             // 多字段表单(建合同)
    fun promptText(player, prompt, initial, onText)         // 单行文本(争议原因等)
}
```
两套实现 + 运行期版本探测:
- `DialogInputService` —— Paper 1.21.6+,用 Dialog API(`Player#showDialog` + dialog body/inputs/actions)。
- `ChatInputService` —— 所有版本兜底,顺序公屏问答 + 超时/取消(直接复用现有 `disputePrompts`/`descriptionPrompts` 的成熟实现)。

`ContractGui` 只依赖 `InputService` 接口;在 enable 时按版本注入实现。**删除** `net.wesjd:anvilgui` 依赖、shadow include 与 relocate;删除 `openTextAnvil`/`openNumberAnvil`/`openSignAnvil`/`anvilSuppressReopen`。

### 3.3 Dialog API 的构建接入(关键决策,见 §6)
当前基线是 **Spigot 1.18.2**(`compileOnly spigot-api 1.18.2`,`api-version: '1.18'`),Dialog API 是 Paper 1.21.6+ 才有的。推荐**方案 A:隔离 Paper 适配器**——
- 给项目加 `compileOnly` Paper 1.21.6 API,**仅** `DialogInputService` 这一个类用到 Paper 符号;
- 所有 Dialog 调用只在"运行期版本 ≥ 1.21.6 且是 Paper"通过后才触达该类,旧服永不加载它(JVM 不校验未触达的类),`api-version` 维持 1.18,Spigot 老服正常退回 `ChatInputService`。
- 这是标准多版本隔离写法,类型安全、无反射。备选见 §6。

### 3.4 签名语义
现在"输名字签署"(`Signature.matches`)只是一道仪式。两个选项:
- **保留**:Dialog 表单加一个"签名"文本输入框校验玩家名;聊天端要求输玩家名。
- **简化**:Dialog/聊天只需点「签署执行」/输 `confirm`。推荐简化(仪式价值低,徒增摩擦)。

---

## 4. 旧→新 屏幕对照

| 旧 | 新 |
|---|---|
| Hub 工作台 | 删除,`/ct` 直达大厅 |
| 合同大厅 / 我的 / 收件箱 / 管理(4 个独立屏 + 跳转键) | 1 个网格 GUI + 视图切换键 |
| 创建:选类型屏 → 表单屏 → 6×铁砧 → 描述聊天 | 1 张 Dialog 表单(高版)/ 聊天向导(低版) |
| 详情 → 确认 GUI → 签名铁砧 | 详情 → 1 次 Dialog/聊天确认 |
| `disputePrompts`/`descriptionPrompts` 聊天 | 收编进 `ChatInputService` |

命令层(`ContractCommand`)保持不变,继续作为无 GUI/脚本化通道。

---

## 5. 分阶段实施

- **P0 — 去铁砧 + 聊天输入(无玩法变化)✅ 已完成**
  - 新增 `ChatInputService`(独立 Listener,单行聊天输入 + 超时/`cancel`/`clear`)。
  - `ContractGui` 的 6 个字段输入、描述、争议全部改走 `ChatInputService`;签名铁砧 → 确认页一键「签署执行」(简化签名)。
  - 删除 `openSignAnvil`/`openTextAnvil`/`openNumberAnvil`/`onChat`/`anvilSuppressReopen`/`DescriptionPrompt`/`DisputePrompt`。
  - 移除 `net.wesjd:anvilgui` 依赖与 shadow relocate。`Signature` 类暂留(已成死代码,P3 清理)。
  - 验证:`compileKotlin` / `test` / `shadowJar` 均 BUILD SUCCESSFUL。
  - 备注:`InventoryButton`/`MenuGui`/`MenuRegistry` 框架与 `InputService` 正式接口推迟到 P1/P2 引入(YAGNI:第二个实现到位时再抽象)。
- **P1 — 大厅落地化 ✅ 已完成**
  - 移植 AH 按钮框架:`gui/framework/InventoryButton`、`Menu`、`MenuRegistry`(按 Inventory 实例路由 click/close,替代标题匹配 + 中央 `when(slot)` 分发)。
  - `/ct` 直达合同大厅;大厅/我的/收件箱合并为一个网格 + 底部视图切换键(含收件箱未读计数);Hub 工作台删除。
  - 管理视图(`openAdmin`)复用同一网格风格,从大厅「管理工作台」键进入、返回大厅。
  - 导航上下文改由闭包(`back: () -> Unit`)承载,删除庞大的 `Session`(10 字段)、`ViewType`、`slotContracts`、`isManagedTitle` 与全部 `handleXClick`。
  - `ContractGui` 仍保留 `onQuit` 清理 draft;命令层 `openHub` → `open`。
  - 验证:`compileKotlin` / `test` / `shadowJar` 均 BUILD SUCCESSFUL。
- **P2 — Dialog 接入 ✅ 已完成(目标改判为 Paper)**
  - **关键决策**:Dialog API 需要服务端原生 Adventure,与原先"shade+relocate Adventure 以支持纯 Spigot 1.18.2"不可共存。经确认 **Contracts 改判 Paper 目标**:
    - 构建改对 `paper-api 1.21.11` 编译(去掉 spigot-api);抬高 compile/test classpath 的 `TARGET_JVM_VERSION` 到 21 以解析 paper-api,但**输出仍为 Java 17 字节码**(release 17),故插件仍能在 Paper 1.18+ / Java 17 上加载。
    - shadowJar **不再 bundle / relocate Adventure**(`net.kyori`),由 Paper 服务端提供;这同时消除了 relocate 与 Dialog 互传 Component 的运行期 `ClassCastException`。
  - `DialogSupport`(`Class.forName` 探测)+ `DialogInputService`(对 Dialog API 类型安全):创建向导→单个原生表单,签署确认→原生 confirmation;`ContractGui` 按 `dialogs != null` 分流,旧 Paper / 无 Dialog 时回退到 P0/P1 的 GUI+聊天。
  - `DialogInputService` 仅在 1.21.6+ 触发 `Class.forName` 成功后才实例化,旧服永不加载。
  - `runServer` 升到 1.21.8 以便在开发服直接验证 Dialog。
  - 验证:`compileKotlin` / `test` / `shadowJar` 均通过;产物核对:无 `net.kyori`/`adventure` 字节(0 条),关键类 major version = 61(Java 17)。
- **P3 — 瘦身收尾 ✅ 已完成**
  - `ViewType`/`Session`/`anvilSuppressReopen` 残留已在 P1 清除。
  - 删除死代码 `Signature.kt` + `SignatureTest.java`(简化签名后已无引用)。
  - `ContractGui` 拆分(899→**747 行**,<800):抽出 `GuiItems.kt`(共享 item 构造)、`ContractRenderer.kt`(item 图标 / 条款预览,单向依赖、零回指)、`PendingAction.kt`。
  - 验证:`test`(16 个测试类全跑)/ `shadowJar` 均 BUILD SUCCESSFUL。
  - 备注:进一步拆成 `HallGui`/`DetailGui`/`CreateFlow` 三控制器需回指或重复;747 行已达标,暂不强拆以免伤内聚。
- **P4 — 接入 Reputations + 拆掉 Contract 自带信誉(计划中)**
  - 背景:已新建独立插件 **`Reputations`**(Vault 模式,ServicesManager `ReputationService` API,bStats 31877)。Contract 不再自带一套信誉逻辑,改为消费它。
  - 构建/清单:Contract 加 `compileOnly(project(":Reputations"))` + `plugin.yml` `softdepend: [Reputations]`。
  - 启动:若 `servicesManager.load(ReputationService)` 非空,注册 4 个字段
    `Contract:completed` / `Contract:cancelled` / `Contract:expired` / `Contract:disputed`(带 displayName/icon)。服务缺席则**优雅降级**(只是不记录)。
  - 钩子替换:`ContractService` 现有 `reputation().recordSettlement/recordCancelled/recordDisputed`
    → 改调 `rep.add(uuid, "Contract:<field>", 1.0)`(同样的三个单点:结算 funnel / cancel 包装 / dispute)。
  - 展示:`ContractRenderer` 的 `repSummary` 改读 `rep.get(uuid, "Contract:*")`;无服务时省略信誉行。
  - 删除:Contract 自带的 `storage/ReputationStore.kt`、`reputation.yml` 接线、`ContractPlugin` 的 reputation flush/bind、`/contract rep` 子命令(由 `/reputation` 取代;或保留为薄转发)。
  - **待定**:Contract 已有的 `reputation.yml` 旧数据是**一次性导入 Reputations** 还是**从零开始**。
  - 验证口径同前:`compileKotlin`/`test`/`shadowJar`;并核对两插件装/不装组合下的降级行为。

每阶段独立可编译、可回归测试。P0 先落地即已显著减负(去铁砧)。

---

## 6. 待你确认的决策

1. **Dialog 构建策略** — ✅ 已定:**改判 Paper 目标**(见 P2)。
   - 实际走向:由于 Adventure relocation 与 Dialog 互传 Component 的硬冲突,A(在 Spigot 基线上隔离 Paper 适配器)不可行;最终对 paper-api 编译 + 不 shade Adventure。仍保留 Java 17 输出与 Paper 1.18+ 加载能力,Dialog 仅 1.21.6+ 启用。
   - 影响:Contracts 不再支持纯 Spigot 服(仍支持 Paper 1.18+)。其余插件不受影响。
2. **签名语义**:保留"输名字签署" vs 简化为一键/`confirm`(推荐简化)。
3. **低版本创建体验**:纯聊天顺序向导 vs 保留精简 GUI + 聊天补字段。
4. **大厅布局**:先硬编码槽位 vs 一开始就做成 AH 那样的 `layout` 字符串可配置。

> 确认后即可从 P0 开始落地。
