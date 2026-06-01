# CubeX 插件体系架构改进方案

> 状态:**决策已锁定(2026-05-31)** · 本文件是「为什么」的**决策依据**。
> 「怎么做」的执行手册见 → [`REFACTOR_PLAYBOOK.md`](REFACTOR_PLAYBOOK.md)(执行 agent 读那份)。
> 参考对象:[NewNanCity/Plugins](https://github.com/NewNanCity/Plugins)(已 clone 至 `../reference/NewNanCity-Plugins`,仅供学习)
> 硬约束:**所有插件作为独立插件发布,每个 jar 必须能单独安装运行。**

---

## 0. 锁定结论(以此为准)

> 注:§3/§5/§6 是当初权衡 A/B/C 的**探索过程记录**(口径偏向 Maven),已被本节与 §7/§8 的最终决策取代;
> 阅读时以本节、§7、§8 为权威,探索章节仅作背景参考。

牛腩小镇值得学的是**「中央化构建治理 + 共享 core 框架 + 分层依赖」这套思想**,不是它的 Kotlin/Gradle 栈本身。

**最终方向(已与用户确认):**
- **路线 B → C**:先做 **Gradle monorepo + 共享 `cubex-core`(Java)**,后期再**逐插件增量迁 Kotlin**。
- **构建工具:Gradle**(从 B 阶段就上,代码先保持 Java),对标牛腩 `buildSrc` 约定插件 + 中央版本管理。
- **`cubex-core` 用 Java**,分层 + 可选模块(非单一大 core),保留到 C 最后再迁。
- **仓库**:monorepo 开发源 + per-plugin 只读镜像门面(`git subtree`,保留历史)。
- **Metro/Railway**:拆成两个完全独立插件,仅共享 `cubex-*` 通用基础设施(详见 §8)。

详细决策见 §7、§8;执行步骤见 [`REFACTOR_PLAYBOOK.md`](REFACTOR_PLAYBOOK.md)。

---

## 1. 现状盘点(CubeX)

| 插件 | 语言 | 构建 | Java 文件数 | api-version | 外部依赖 |
|------|------|------|------------|-------------|---------|
| BookLite | Java | Maven | 16 | 1.18 | — |
| Contracts | Java | Maven(+gradle 子模块) | 40 | 1.18 | Vault |
| EcoBalancer | Java | Maven | 46 | 1.16 | Vault |
| FAWEReplacer | Java | Maven | 7 | 1.20 | WorldEdit |
| Metro | Java | Maven(+gradle 子模块) | 185 | 1.18 | — |
| MountLicense | Java | Maven | 34 | 1.18 | — |
| Railway | Java | Maven | 242 | 1.18 | — |
| RuleGems | Java | Maven | 119 | 1.16 | LuckPerms, Vault |
| GigHub | (Kotlin/空) | Maven 多模块 | 0 | — | 第三方(`io.eliasnvx`,非 CubeX) |

**观察到的问题:**

1. **无共享代码层**:8 个 `org.cubexmc` 插件各自 `extends JavaPlugin`,
   配置加载、日志、i18n、调度、资源清理全部各写一遍。
2. **版本漂移**:sqlite 在 BookLite=3.45 / EcoBalancer=3.49 / RuleGems=3.45;
   `api-version` 在 1.16 / 1.18 / 1.20 之间不统一;Java 目标版本也各写各的。
3. **构建配置重复**:每个 `pom.xml` 都重复 shade、compiler、resource 配置——
   就像上一轮我们要**手动给 4 个 pom 分别加 sqlite 原生库过滤器**,这正是重复治理的代价。
4. **Railway 是 Metro 的 fork**:Railway 当初为复用 Metro 核心而 fork,但物理/发车完全不同,
   且 diff 实测显示连核心领域(Stop/MetroAPI/PortalManager)都已各自重写到面目全非(详见 §8)。
   维持 fork-merge 是纯负担——决策为「拆成两个独立插件 + 仅共享 cubex-* 通用基础设施」。
5. **9 个独立 repo**:改一处公共逻辑要在多个 repo 重复操作,无统一 CI / 版本目录。

---

## 2. 牛腩小镇架构拆解(5 根支柱)

```
NewNanPlugins/                         # 单一 monorepo
├── settings.gradle.kts                # 聚合所有子项目
├── buildSrc/                          # ② 中央构建治理
│   ├── Versions.kt                    #    所有版本号常量
│   ├── Dependencies.kt                #    所有依赖坐标(分组:Core/Database/Command/Config/...)
│   ├── PluginMetadataExtensions.kt    #    plugin.yml 元数据 DSL
│   └── newnancity-plugin.gradle.kts   # ③ 约定插件(编译/shadow/relocate/跑测试服一次写好)
├── modules/                           # ④⑤ 共享框架,分层 + 可选
│   ├── core/   (BasePlugin 必选)
│   ├── config/ gui/ i18n/ network/    #    按需引入
└── plugins/                           # 具体插件,每个一行 id("newnancity-plugin") 复用约定
    ├── tpa/ railarea/ feefly/ ...
```

| # | 支柱 | 机制 | 对应你们的痛点 |
|---|------|------|---------------|
| ① | **单仓库 monorepo** | 一个 git repo 聚合所有子项目,并行构建 | 9 repo 分散管理 |
| ② | **中央版本/依赖目录** | `Versions.kt` + `Dependencies.kt` 单一事实来源 | 版本漂移 |
| ③ | **约定插件** | `newnancity-plugin.gradle.kts` 把通用构建写一次,各插件复用 | pom 配置重复 |
| ④ | **共享 core 框架** | `BasePlugin` 提供资源清理(Terminable)、日志、调度 DSL、i18n、事件 | 各插件重复造轮子 |
| ⑤ | **分层 + 可选模块** | core→config/gui/i18n/network→plugins,严格单向依赖 | 关注点不分离 |

**两条值得照搬的硬规范:**
- **四层单向依赖**:基础层(配置/数据/工具/API 适配)→ 逻辑层(Manager/调度)→ 事务层(事件/指令/对外服务)→ 主插件类。上层可调下层,下层不可调上层。
- **Manager 懒加载 + 生命周期绑定**:`val xxxManager by lazy { XxxManager(this).also { bind(it) } }`,杜绝 `lateinit`,资源随插件自动释放。

---

## 3. 差距分析:为什么不能照搬

| 维度 | 牛腩 | CubeX | 能否直接搬 |
|------|------|-------|-----------|
| 语言 | Kotlin | Java(~690 文件) | ❌ 它的 `BasePlugin`/DSL/Terminable 重度依赖 Kotlin(`by lazy`、扩展函数、协程) |
| 构建 | Gradle + `buildSrc` 约定插件 | Maven | ⚠️ 等价物是 **Maven 父 POM**,机制不同但目的相同 |
| 仓库 | 1 monorepo | 9 独立 repo | ⚠️ 可合并,但要兼顾「独立发布」(见 §4) |
| 框架 | 自研 core 框架 | 无 | ✅ 思想可学,需用 Java 重新实现一个精简版 |

结论:**学思想(②③④⑤ + 两条规范),用 Java/Maven 落地**。

---

## 4. 核心澄清:monorepo 与「独立发布」不冲突 ⭐

这是你最关心的点。**结论:monorepo 只是「开发期的源码组织方式」,不改变「发布期每个插件仍是独立 jar」。**

原因有两层:

**(1) 构建产物仍是 N 个独立 jar。**
Maven 多模块 monorepo `mvn package` 会为每个插件模块产出各自的 `RuleGems-x.jar`、`EcoBalancer-x.jar`……
服务器管理员照旧只下载需要的那一个,丢进 `plugins/` 即可,**不需要安装"全家桶"**。

**(2) 共享 core 被 shade 进每个插件 jar,且 Bukkit 天然隔离。**
- 共享的 `cubex-core` 作为内部库,用 maven-shade 打进每个插件 jar(就像现在 sqlite 被打进去一样)。
- **Bukkit/Paper 给每个插件独立的 `PluginClassLoader`**:即使 RuleGems 内置 core v1.2、EcoBalancer 内置 core v1.3,两者在各自类加载器里互相隔离,**不会冲突**。
- 因此甚至不强制做包重定位(relocation);需要时(如同时被其他插件以 API 方式引用)再 relocate 到 `org.cubexmc.libs.core` 即可。

> 一句话:**你们继续"每个插件独立发布",monorepo 只是让你们在一个地方维护它们、共享 core、统一版本。** 二者可以并存。

---

## 5. 三条路线详解(含独立发布影响)

### 路线 A — 轻量:Maven 父 POM + 中央版本管理
- **做什么**:新增一个 `cubex-parent` 父 POM(用 `<dependencyManagement>` 和 `<pluginManagement>` 集中版本、shade 配置、编译目标、`api-version`)。各插件 `pom.xml` 顶部声明 `<parent>` 即可继承,**sqlite 过滤器这类配置只写一次**。
- **学到**:②③
- **仓库**:9 repo **保持不变**(父 POM 可作为已发布构件,或在 monorepo 里;A 阶段甚至可让各 repo 引用同一父 POM 坐标)。
- **独立发布**:零影响。
- **风险/工期**:低 / 1–2 天。
- **好处**:立刻消除构建配置重复和版本漂移,几乎无迁移成本,是后续一切的地基。
- **局限**:不解决"共享业务代码"(配置/i18n/日志仍各写各的)。

### 路线 B — 中量:Maven monorepo + 共享 core 库(推荐目标)⭐
- **做什么**:
  1. 建 monorepo,8 个 `org.cubexmc` 插件作为子模块。
  2. 新增 **`cubex-core`** Java 库:提供 `CubexPlugin` 基类(统一配置加载、YAML/i18n 工具、日志前缀、调度封装、资源自动清理 `AutoCloseable` 模式——Java 版的 Terminable)。
  3. 各插件改为 `extends CubexPlugin`,core 经 shade 打进各自 jar。
  4. 顺手厘清 **Metro/Railway 同源问题**。
- **学到**:①②③④⑤ + 两条规范(用 Java 表达)。
- **仓库**:合并为 monorepo(策略见 §6)。
- **独立发布**:**不受影响**(见 §4),每个插件照旧独立出 jar。
- **风险/工期**:中 / 1–2 周(主要是 core 设计 + 各插件接入)。
- **好处**:消除重复造轮子,新插件起步快;统一生命周期/配置/i18n;一处修 bug 全插件受益。
- **局限**:需要一次性的接入改造;core API 要审慎设计避免返工。

### 路线 C — 重量:Kotlin + Gradle monorepo(全面对标)
- **做什么**:照牛腩技术栈,把所有插件重写为 Kotlin + Gradle,直接复用其 `BasePlugin`/模块体系。
- **学到**:全部,且最彻底。
- **独立发布**:不受影响,但代价是 **~690 个 Java 文件全部重写**。
- **风险/工期**:高 / 数周–数月。
- **好处**:最现代、最一致;长期可维护性最好。
- **局限**:等于重做所有插件,期间易引入回归;除非有长期专人投入,否则不建议。

| | A 轻量 | B 中量 ⭐ | C 重量 |
|--|--------|---------|--------|
| 语言 | Java(不变) | Java(不变) | Kotlin(重写) |
| 工期 | 1–2 天 | 1–2 周 | 数周–数月 |
| 风险 | 低 | 中 | 高 |
| 消除构建重复 | ✅ | ✅ | ✅ |
| 消除业务代码重复 | ❌ | ✅ | ✅ |
| 独立发布 | ✅ | ✅ | ✅ |

---

## 6. 仓库策略(各方案好处 — 兼顾独立发布)

**前提**:因为发布产物始终是 N 个独立 jar,「monorepo vs 多 repo」纯粹是**开发体验/源码组织**的选择,**与发布方式无关**。

| 方案 | 好处 | 代价 | 适合 |
|------|------|------|------|
| **保持 9 个独立 repo** | 每个插件独立 issues/release/版本号;可单独授权协作者;clone 轻 | 版本漂移、构建配置重复、共享代码只能靠"发布构件或复制粘贴" | 只走路线 A |
| **monorepo · 保留历史合并** | 单一版本目录、共享 core 一处维护、跨插件原子重构、一套 CI;**且保留各 repo 提交历史**(`git subtree`) | 合并过程略复杂 | 走 B/C 且在意历史 |
| **monorepo · 全新开始** | 同上,且结构最干净;旧 repo 归档保留 | 丢失逐文件提交历史(可在归档 repo 里查) | 走 B/C 且想轻装上阵 |

**关于「独立发布」在 monorepo 下如何运作(打消顾虑):**
- **独立版本号**:每个子模块在自己的 `pom.xml` 里有独立 `<version>`,各自按需升级,互不绑死。
- **独立 release / tag**:用带前缀的 git tag(如 `rulegems-v1.0.9`、`metro-v1.2.0`)区分;CI 可按 tag 只打对应插件。
- **独立产物**:`mvn -pl :RuleGems package` 只构建单个插件;CI 矩阵可并行为每个插件产 jar 并各自上传到 SpigotMC/Modrinth。
- **共享 core**:作为 monorepo 内部模块,shade 进每个插件 jar,使用者无感知。

> 推荐:走 B 时采用 **monorepo + 保留历史合并 + 每模块独立版本/tag**——
> 既拿到 monorepo 的全部开发收益,又 100% 保留「独立发布」的对外形态。

---

## 7. 已确定的方向(决策记录)

> 以下为与用户确认后锁定的方向,后续设计据此展开。

- **目标路线:B → C**。先做 **Maven→Gradle monorepo + 共享 `cubex-core`(Java)**(路线 B),
  后期再**逐插件、增量**地迁移到 **Kotlin**(路线 C)。B 的架构(模块边界、版本目录、core API、
  四层依赖、shade/独立发布)语言无关,C 全盘继承,**B 的投入不浪费**。
- **构建工具:Gradle**(从 B 阶段就上,代码先保持 Java)。趁纯 Java 时把构建迁到 Gradle 最干净,
  日后转 Kotlin 只需加 `kotlin("jvm")`,避免"换构建 + 换语言"两件事同时发生。对标牛腩
  `buildSrc` 约定插件 + 中央版本管理。
- **`cubex-core` 语言:Java**,且**保留到 C 的最后再考虑迁 Kotlin**。
  理由:Kotlin 插件调用 Java core 完美无摩擦;转 C 时顺序是"插件先 Kotlin 化,core 最后"。
- **独立发布:不变**。monorepo 仍为每个插件产独立 jar,core 经 shade 打进各 jar,
  每模块独立 `version` + 带前缀 git tag(如 `rulegems-v1.0.9`)。

### 落地阶段(本报告不执行,待逐阶段确认)

1. **第 0 阶段(Gradle 地基)**:建 Gradle monorepo 骨架——`settings.gradle.kts` 聚合、
   `buildSrc`(`Versions`/`Dependencies`/`cubex-plugin` 约定插件)、根 `build.gradle.kts`。
   先用 1 个插件(如 BookLite)验证"Gradle 构建 + shadow + plugin.yml 生成 + 跑测试服"跑通。
2. **第 1 阶段(逐插件迁构建)**:把 8 个插件从 Maven 逐个迁到 Gradle 子模块(代码不动,纯换构建)。
3. **第 2 阶段(core 设计)**:设计 `cubex-core` Java API(基类 `CubexPlugin` + 配置/i18n/日志/调度/
   资源清理),先在 BookLite 试点接入。
4. **第 3 阶段(逐插件接入 core)**:从小到大改为 `extends CubexPlugin`,顺带厘清 Metro/Railway 同源。
5. **第 4 阶段(CI)**:GitHub Actions 矩阵构建,按 tag 独立发布各插件。
6. **(后期)路线 C**:新插件直接 Kotlin;老插件按"小→大"逐个 .java→.kt;`cubex-core` 最后迁。

---

## 8. 决策记录(续)与待定项

### 已定(2026-05-31)

- **#1 仓库合并:monorepo 开发源 + per-plugin 只读镜像门面**(Symfony/Laravel 同款模式)。
  - 用 `git subtree` 把各 repo 导入 monorepo 的 `plugins/<name>/`,**保留完整历史**(生产级插件,
    `git blame`/溯源价值高)。
  - **旧的各插件 repo 不归档**,降级为「只读代码镜像 + wiki/issues/releases 门面」,
    **继续作为 Spigot/Modrinth 的指向目标**。CI 用 `git subtree split`(或 `splitsh/lite`)
    把各插件子目录历史持续推送到对应独立 repo。
  - **wiki 不受影响**:GitHub wiki 是独立的 `repo.wiki.git`,与代码位置无关,原地不动。
  - **发布**:CI 给每插件独立 tag(如 `rulegems-v1.0.9`)+ 构建 jar(已 shade cubex-core)
    上传到对应 repo 的 Release + Spigot + Modrinth。
  - **注意**:镜像 repo 因缺共享 `cubex-*` 源码而**不可独立构建**;它只作展示/发布门面,
    实际构建在 monorepo。贡献者 PR 提到 monorepo(镜像只读)。
  - **待细化(非阻塞)**:issues 放各插件 repo(匹配 Spigot/Modrinth 报 bug 链接)还是
    集中到 monorepo 用标签区分。
- **#4 reference 排除版本控制:已执行**。已删除 `reference/NewNanCity-Plugins/.git`
  (--depth 1 浅克隆,无历史价值),现为纯学习资料,永不入版本控制;需更新时重新 clone。
- **#3 cubex-core 形态:分层 + 可选模块**(非单一大 core)。小而必选的 `cubex-core`
  + 按需引入的可选模块,避免 god-module 和 jar 膨胀。模块范围**从实测重复出发**:

  | 模块 | 必选? | 解决的重复(实测文件数) |
  |------|--------|------------------------|
  | `cubex-core` | 必选 | 基类/生命周期/日志/消息颜色工具(69)/资源清理 |
  | `cubex-config` | 可选 | 配置加载合并(60) |
  | `cubex-i18n` | 可选 | 各自 lang/*.yml 多语言 |
  | `cubex-database` | 可选 | JDBC/SQLite 连接(14) |
  | `cubex-command` | 可选 | 命令/补全注册(39) |
  | `cubex-gui` | 可选(后期) | GUI 框架 |

### Metro/Railway:源码 diff 实测 + 修正后的决策(#2)

**背景更正(用户澄清)**:Railway 当初是独立项目,fork Metro 仅为"复用 Metro 核心功能";
但 Railway 与 Metro 的**物理和发车处理完全不同**。即 fork 是"代码复用"的手段,不是"换皮"。

**源码 diff 实测**(`Metro/src` vs `Railway/src`,Metro 111 java / Railway 163 java):

| 文件 | 差异行数 | 性质 |
|------|---------|------|
| `api/MetroAPI.java` | 1566 | 几乎重写 |
| `model/Stop.java` | 929 | Metro ~72 行 → Railway ~470 行,**核心领域模型已重写** |
| `manager/PortalManager.java` | 897 | 深度分歧 |
| `service/TicketService.java` | 594 | 深度分歧 |
| `spatial/Octree.java` | 432 | 深度分歧 |
| GUI/listener/command 一批 | 100~400 | 逻辑漂移 |
| `ColorUtil`/`StopManager`/部分 view | 2~9 | 仍基本相同 |

- **Railway 新增**(Metro 没有):`physics/`、`control/`、`estimation/`、`placeholder/`、
  `train/{TrainConsist,TrainNavigator,TrainInstance,...}`、`service/{DispatchStrategy,TrainSpawner,ServiceEtaCalculator,...}`、
  `util/{Quaternion,MathUtil,MinecartPhysicsUtil,MountAwareTeleportUtil,FaceUtil,IntVector3}` —— 一整套高级物理/发车引擎。
- **Metro 独有**:`bedrock/`(5 文件;Railway 改用 `util/BedrockPlayerUtil`+`MountAwareTeleportUtil`)。

**结论:不抽共享领域模块,改为「全面拆成两个独立插件 + 仅共享通用基础设施」。**
"单源双 jar"方案**作废**(它假设只是换皮,与事实不符;硬塞会逼运行时分支判断物理,丑陋)。
"`metro-core` 共享领域模块"也**否决**:连 Stop/MetroAPI/PortalManager 等核心都各自重写到面目全非,
强抽共享实现 = 巨大调和工程,且拖累两侧既有行为。

### Metro/Railway 决策(已定)

- **Metro 与 Railway 做成两个完全独立的插件模块**,各自拥有全部领域代码(Stop/Line/GUI/物理/发车),
  **互不 merge,取消 upstream 关系**。它们已是事实上的两个插件,此决策只是承认现实。
- **二者只通过 `cubex-*` 通用模块共享"插件无关"的基础设施**,顺带消重:
  - `ColorUtil`/`SchedulerUtil`/`OwnershipUtil`/`LocationUtil` → `cubex-core` 工具
  - `ConfigFacade`/`ConfigUpdater` → `cubex-config`
  - 命令脚手架 `CommandRegistration` 等 → `cubex-command`
  - 各自 `lang/*.yml` → `cubex-i18n` 机制
- **领域代码各留各的**,各自独立 version/release/jar,自由演进。
- **后续可选**:若日后发现某块领域逻辑仍在两边重复,再回头抽一个薄 `metro-core` 不迟;
  当前数据表明领域层已硬分叉,不值得现在做。

### 仍待细化(非阻塞,可在设计阶段处理)

- `cubex-core` 各可选模块的**具体 API 边界**(第 2 阶段 core 设计时细化)。

> **所有方向性决策已锁定。** 执行步骤、文件骨架、验收门见 → [`REFACTOR_PLAYBOOK.md`](REFACTOR_PLAYBOOK.md)。
