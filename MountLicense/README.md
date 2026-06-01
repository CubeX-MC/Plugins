# MountLicense

![](https://bstats.org/signatures/bukkit/MountLicense.svg)

载具牌照插件。把马、驴、骡、猪、炽足兽、羊驼、骆驼、乐魂、鹦鹉螺、船、矿车等可骑乘实体注册为玩家的私有交通资产，并提供基础的所有权与索引管理。

## 当前状态：Phase 0 / 1 / 2 / 3 / 5a

按 [PLAN.md](PLAN.md) 的阶段划分，本仓库目前完成：

- **Phase 0** — 项目骨架、配置、本地化资源（中英双语）。
- **Phase 1** — 注册系统：License 物品、PDC 写入、YAML 索引、玩家命令与管理命令。
- **Phase 2** — 保护与停车：防非主人骑乘、防玩家伤害/破坏、防开容器、防牵绳；park/unpark/lock/unlock/release 命令；entity 死亡自动清理索引。
- **Phase 2 增强** — 自动停车模式（默认开启，下马即停、上马即活），单玩家载具数量上限可配置。
- **Phase 3** — Key 物品 + 召回 + 定位：右键天空召回最近 / 已绑定载具；蹲下右键载具绑定钥匙；冷却 + 安全位置校验；离线载具显示最近位置；`/ml recall` / `/ml locate` / `/ml key unbind` 命令。
- **Phase 5a** — 信任列表：每辆载具独立 trustees 集合；信任者可骑乘/开容器/牵绳/免伤；不可召回、不可改名单；`/ml trust` / `/ml untrust` 命令；`/ml info` 显示当前信任名单。

后续阶段（**未实现**）：

- Phase 4：车站系统 — **已决定取消**（私人马厩用栅栏+Phase 2 已足够，公共驿站归并到 Phase 5b）
- Phase 5b：公共驿站 + 租赁（如果需要"市政自行车"风格的体验）
- Phase 6：Vault 费用入公共账本 / Dynmap 标记 / Lands 区域校验

## 兼容性

- Java 17
- 编译基线：Spigot API 1.18.2，`plugin.yml` API version 为 `1.18`
- 运行目标：Spigot/Paper 1.18.2 及以上
- 可选：Vault（用于注册收费）

`vehicle-profiles.yml` 默认保留较新版本实体名，旧版本会在启动时跳过未知实体类型并写入 warning，而不会中断整个 profile 加载：

| 实体类型 | 最低版本 | 1.18.2 行为 |
|---|---:|---|
| HORSE / DONKEY / MULE / SKELETON_HORSE / ZOMBIE_HORSE | 1.18.2 | 可用 |
| LLAMA / TRADER_LLAMA | 1.18.2 | 可用 |
| PIG / STRIDER | 1.18.2 | 可用；默认要求已有鞍 |
| BOAT / MINECART / CHEST_MINECART | 1.18.2 | 可用 |
| CHEST_BOAT 与各木种 boat/chest boat/raft 实体名 | 1.19+ / 新版本 API | 1.18.2 跳过 |
| CAMEL / CAMEL_HUSK | 1.20+ / 新版本 API | 1.18.2 跳过 |
| HAPPY_GHAST | 新版本 API | 1.18.2 跳过 |
| NAUTILUS / ZOMBIE_NAUTILUS | 新版本 API | 1.18.2 跳过 |

## 使用流程（玩家）

1. 管理员发放 License：`/ml admin give <玩家> license [数量]`
2. 玩家拿到 License（默认为纸张）后**蹲下右键**目标坐骑或载具
   - 切到主手 License 时会显示 actionbar 提示，提醒“下蹲右键可注册”
3. 满足条件则注册成功，License 消耗一张
4. 注册后会生成类似 `ABC-123` 的短牌照，坐骑/载具名称默认显示该牌照
5. 查看自己的载具：`/ml list`，查看详情：`/ml info <牌照>`

## 命令

`park|unpark|lock|unlock|release` 通过**看向目标载具**触发。

| 命令 | 权限 | 说明 |
|---|---|---|
| `/ml help` | mountlicense.use | 查看帮助 |
| `/ml list` | mountlicense.use | 列出自己的载具 |
| `/ml info <牌照>` | mountlicense.use | 查看载具详情（支持牌照前缀；完整 UUID 仍兼容） |
| `/ml park` | mountlicense.park | 停车（生物关闭 AI，标记 PARKED） |
| `/ml unpark` | mountlicense.park | 取消停车（恢复 ACTIVE） |
| `/ml lock` / `/ml unlock` | mountlicense.park | 切换 LOCKED 状态（Phase 2 与 PARKED 等价保护） |
| `/ml release` | mountlicense.park | 解除注册；首次执行只警告，30 秒内重复确认 |
| `/ml recall [牌照]` | mountlicense.key.use | 召回（无牌照 = 召回最近的；有牌照 = 召回指定的） |
| `/ml locate <牌照>` | mountlicense.use | 查询载具最近坐标，并显示是否在已加载区块 |
| `/ml key unbind` | mountlicense.key.use | 解除主手钥匙的绑定 |
| `/ml trust <玩家>` | mountlicense.trust | 看着自己的载具，把目标玩家加入信任名单 |
| `/ml untrust <玩家>` | mountlicense.trust | 从信任名单移除 |
| `/ml admin inspect` | mountlicense.admin.inspect | 视线对准实体查看其注册数据 |
| `/ml admin give <玩家> license\|key [n]` | mountlicense.admin.give | 发放牌照/钥匙 |
| `/ml admin reindex` | mountlicense.admin.reindex | 扫描已加载实体的 PDC，重建内存索引 |
| `/ml admin reload` | mountlicense.admin.reload | 补齐缺失的默认配置/语言/profile 文件，并重载配置与语言 |

## 权限

- `mountlicense.use` (默认 true)
  - `/ml help`, `/ml list`, `/ml info`, `/ml locate`
- `mountlicense.register` (默认 true)
- `mountlicense.park` (默认 true)
- `mountlicense.key.use` (默认 true)
- `mountlicense.trust` (默认 true)
- `mountlicense.admin.bypass` (默认 op) — 管理员绕过所有保护（骑乘/伤害/开箱/牵绳）和数量上限

## 钥匙使用流程

1. 管理员发放钥匙：`/ml admin give <player> key [n]`（默认胡萝卜钓竿，可在 `config.yml` 改）
2. 玩家蹲下右键自己已注册的载具，把这把钥匙**绑定**到该载具
3. 之后只要主手拿着钥匙**右键天空**就会召回那辆载具
4. 想换一辆，重新蹲下右键即可覆盖绑定
5. 想让钥匙变回通用（"召回最近"模式），执行 `/ml key unbind`

绑定的钥匙会在物品名加上 `(#牌照)` 后缀，并在 lore 显示绑定的载具牌照。

## 配置

主要配置项见 `config.yml`：

- `language`: `zh_CN` 或 `en_US`
- `economy.enabled` + `economy.register_cost`: Vault 注册收费
- `vehicle-profiles.yml` 的 `requiresTamedOwner`: 指定 profile 是否要求驯服且所有者匹配
- `vehicle-profiles.yml` 的 `requiresSaddle`: 指定 profile 是否要求已装鞍（默认用于 pig / strider）
- `registration.require_empty_vehicle`: 船/矿车注册前是否需为空（默认 true）
- `registration.max_interact_distance`: 防作弊距离上限（默认 6.0）
- `registration.display_name_format`: 注册后实体显示名格式；默认 `%plate%`，也可使用 `%player%` / `%profile%`
- `cooldowns.register`: 注册操作冷却（默认 2 秒）
- `persistence.autosave_interval_ticks`: 索引自动保存间隔（默认 60 秒）

车辆类型 → profile 映射见 `vehicle-profiles.yml`，默认 profile：horse / llama / pig / strider / camel / nautilus / happy_ghast / boat / minecart。旧服务端不认识的新实体名会被跳过，不影响其他 profile。

## 数据文件

- `plugins/MountLicense/vehicles.yml`：载具索引（玩家可读，但**不要手动编辑**。如需修复请用 `/ml admin reindex`）

## 停车模式

`config.yml` 顶层 `park_mode`：

- `auto`（默认）：主人**下马自动 park**（关 AI、存位置），**上马自动 unpark**。`/ml park` `/ml unpark` 仍可手动强制覆盖。
- `manual`：只有命令能改变状态，下马不再自动停泊。

LOCKED 状态不受自动模式影响——锁定的载具上下马不会改变状态，必须显式 `/ml unlock` 才能让自动逻辑重新生效。

## 数量上限

`config.yml` 的 `registration.max_vehicles_per_player`（默认 `-1` = 不限制）。

- 注册时检查 `byOwner().size() >= max` → 拒绝并提示玩家先 `release` 旧的
- 持有 `mountlicense.admin.bypass` 的管理员不受此限制
- 不区分 profile（horse、boat、minecart 等统一计入）

推荐起步值：

| 玩家场景 | 建议上限 |
|---|---|
| 普通生存玩家 | 10-15（速度马、驮兽、船、矿车都有了还有冗余） |
| 工会成员 | 20-30（要管理公共马厩） |
| 富裕老玩家 | 50+ 或不限制 |

## 保护规则（Phase 2）

默认开启，可在 `config.yml` 的 `protection` 段关闭：

- **block_player_mount**：非主人无法骑乘 / 进入注册载具
- **block_player_damage**：玩家攻击（含弓箭、弩、三叉戟）不会伤害注册载具
- **block_player_destroy**：boat/minecart 不会被非主人拆解
- **block_inventory_access**：donkey/mule/llama/chest_boat/chest_minecart 容器只有主人可开
- **block_leash**：注册的载具禁止被牵绳

**有意保留的伤害**：环境伤害（坠落、火、溺水）、怪物攻击仍然有效。设计意图是保护免受玩家恶意行为，而不是变成无敌坐骑。

死亡/合法破坏会触发 `cleanup_on_death`，自动清理索引记录。

## 信任列表（Phase 5a）

每辆载具有独立的 trustees 集合（不是全局"我信任 Alice"）。语义：

| 操作 | owner | trustee | 路人 | 管理员 (bypass) |
|---|---|---|---|---|
| 骑乘 / 进入 | ✅ | ✅ | ❌ | ✅ |
| 受玩家攻击伤害 | 免疫 | 免疫 | 免疫 | 可伤 |
| 开容器（驴/骡/储物船等） | ✅ | ✅ | ❌ | ✅ |
| 牵绳 | ✅ | ✅ | ❌ | ✅ |
| `/ml recall` 召回 | ✅ | ❌ | ❌ | ✅ |
| `/ml trust` / `/ml untrust` | ✅ | ❌ | ❌ | ✅ |
| `/ml release` 解除注册 | ✅ | ❌ | ❌ | ✅ |
| 钥匙绑定 | ✅ | ❌ | ❌ | ✅ |

**关键点**：trustee 可以**使用**载具但不能**调度**或**移交**——召回、解散、改名单都是 owner 专属。这避免 trustee 把 owner 的马召回自己家的乱套行为。

**使用流程**：
1. 玩家 A 看着自己的马，执行 `/ml trust B`
2. 玩家 B 走过来，可以蹲坐右键上马 → 不会被保护拦截
3. B 下马 → auto-park 模式下也会标记 PARKED（trustee 的下马也算）
4. A 想撤回信任：`/ml untrust B`
5. 信任名单上限可在 `config.yml` 的 `trust.max_trustees_per_vehicle` 配置

**`/ml info <牌照>`** 会在末尾显示 `信任名单: X, Y, Z` 或 `信任名单: 无`。

**当前决策**：0.1.x 只支持对**在线玩家**执行 trust/untrust（精确在线名查找）。离线 UUID 解析会带来改名、缓存和歧义提示问题，推迟到需要持久玩家索引时再设计。

## 召回配置

`config.yml` 的 `recall:` 段：

```yaml
recall:
  search_radius: 100              # 召回搜索范围（格）
  cooldown_seconds: 30            # 每玩家冷却（秒）
  require_safe_destination: true  # 拒绝在不安全位置召回
  wake_on_recall: true            # 召回后强制 ACTIVE + 开 AI
```

- 安全目的地要求：脚下和头部方块必须可通过、非液体、非危险方块；脚下方块下方必须是安全实心方块。
- **未加载区块的载具召回会失败**，只显示最近位置（Phase 3 设计选择，避免 R3 风险）
- **矿车不能召回**（`vehicle-profiles.yml` 的 minecart profile 默认 `summon: false`）
- 召回会**重置自动 park 状态**：召回的瞬间载具变 ACTIVE，玩家可以立刻骑上

## 已知限制

- **离线/远方载具无法召回**：必须在 100 格内且实体已加载。这是有意设计——不开 chunk-load 召回，保证服务端性能和 Folia 兼容。要找回远方的马，用 `/ml locate <牌照>` 看坐标自己走过去。
- **trust/untrust 只支持在线玩家**：这是 0.1.x 的明确取舍，不做离线 UUID 猜测。
- **PDC 写入主线程同步**，大量并发注册无优化。MVP 使用场景不会触发瓶颈。
- **真实服务器回归仍需执行**。当前已有 Maven 单元测试覆盖核心纯逻辑；Bukkit 事件、实体 AI、Inventory、PDC 与 Vault provider 行为仍需要实服检查。

## 构建

```bash
mvn clean package
```

产物：`target/mountlicense-0.1.0.jar`

## 安装测试

回归记录表见 [docs/manual-regression.md](docs/manual-regression.md)。发布前应在真实 Spigot/Paper 服务器把结果填入该文件，尤其是实体交互、重启持久化和 Vault 收费路径。

1. 拷贝 jar 到服务器 `plugins/`
2. 启动服务器，会生成 `plugins/MountLicense/` 含 `config.yml`、`vehicle-profiles.yml`、`lang/`
3. 执行 `/ml admin give <你自己> license 5` 自测发放
4. 驯服一匹马、蹲下右键 → 应提示注册成功
5. `/ml list` 查看
6. 重启服务器 → `/ml list` 数据应保留

### Phase 2 验证

7. 让另一个玩家骑你的马 → 应被拒绝，提示 "属于 XXX"
8. 让另一个玩家打你的马 → 应无伤
9. 让另一个玩家开你驴的箱子 → 应被拒绝
10. 看着马执行 `/ml park` → 马应停止漫游（AI 关）
11. 看着马执行 `/ml release` → 警告；30 秒内再执行 → 解除注册
12. 让一只僵尸打你的马 → 应有伤（验证只挡玩家不挡怪物）

### Phase 2 增强验证

13. `park_mode: auto` 时骑上注册的马走两步下马 → 应静止；再上马 → 应能正常移动
14. `/ml lock` 锁定后下马 → 状态保持 LOCKED，**不**会被自动 park 覆写
15. 把 `registration.max_vehicles_per_player` 设为 2，再尝试注册第 3 辆 → 应被拒绝并提示

### Phase 3 验证

16. `/ml admin give <你> key 1` → 收到胡萝卜钓竿名为"载具钥匙"
17. 走到你已注册的马旁，**蹲下右键**它 → 钥匙名变成类似 `载具钥匙 (#ABC-123)`
18. 走到 50 格外（确保马未跟来），**右键天空** → 马应被传送到你脚下
19. 立即再右键天空 → 应提示冷却中（30 秒）
20. 让其他玩家把你的马打死 → 你再右键天空召回 → 应提示"找不到该牌照"
21. `/ml key unbind` → 钥匙名恢复为"载具钥匙"；再右键天空 → 召回最近的拥有载具
22. 把马拉到离你 200 格（超出搜索半径）下马 → 你右键天空 → 应提示"附近 100 格内没有"
23. `/ml locate <牌照>` → 显示最近坐标 + 是否已加载
24. 注册一辆矿车，绑定钥匙，召回 → 应提示"该载具不支持召回（如矿车）"

### Phase 5a 验证

25. 玩家 A 看着自己的马 `/ml trust B` → A 收到成功提示，B 收到通知
26. 玩家 B 走过来骑马 → 应能上马；不再触发 protection.mount_blocked
27. 玩家 B 打开 A 的驴的箱子 → 应能打开
28. 玩家 B 试 `/ml recall <A 的载具牌照>` → 应被拒绝（trustee 不能召回）
29. 玩家 B 试 `/ml release` 看着 A 的马 → 应被拒绝
30. A `/ml info <牌照>` → 末尾应列出 B 的名字；`/ml untrust B` 后再 info → 应显示"无"
31. 把 `trust.max_trustees_per_vehicle` 设为 1，A 已 trust 了 B，再 trust C → 应提示已满
