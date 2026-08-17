# StateCharge

付费限时状态插件：玩家用 Vault 货币购买限时的变小 / 变大 / 飞行等状态。

## 定位

服务器想卖"临时特权"时，通常要么硬编码几个固定商品，要么把整套 RPG 系统搬进来。
StateCharge 只做中间那一层：**一个配置驱动的限时状态框架**。
服主在 `config.yml` 里增删状态、改价格与时长，只有新增**效果类型**时才需要改代码。

计时按**在线时长**：离线暂停、不扣时也不生效，避免玩家买完下线白白烧掉时长。

**不做什么**（避免误装）：

- 不是 RPG / 技能 / 称号系统，只管"付费换一段时间内的状态"。
- 不做现实时间倒计时（买了就开始烧，无论在不在线）——那是另一种语义，v1 不做。
- 不自带商店 GUI，交互走命令。

## 功能特性

- 内置 `scale`（变小/变大）与 `fly`（飞行）两种效果类型，内置 small / giant / fly 三个状态。
- 状态完全由配置定义：id、展示名、价格、每份时长、累计上限、购买权限、互斥组。
- 互斥组：同组状态不能同时生效（例如变小与变大）。
- 重复购买**累加**剩余时长，受 `max-stack-seconds` 限制。
- 到期提醒：阈值聊天提示 + 最后 N 秒 actionbar 倒计时。
- 管理员可免费发放或清除状态。
- 数据落盘带脏标记刷新，文件损坏时回退 `.bak`。

## 运行要求

| 项 | 要求 |
|---|---|
| 服务端 | Paper 1.21.x（体型走 1.20.5+ 属性 API `Attribute.SCALE`，**不需要 ProtocolLib**） |
| Java | 17 |
| 必需依赖 | **Vault** + 一个 Vault 经济 provider（缺失时插件自动禁用） |
| 可选依赖 | Essentials / CMI / EssentialsX（仅用于保证加载顺序） |
| Folia | 支持 |

## 安装

1. 装好 Vault 与一个经济 provider。
2. 把 `statecharge-<version>.jar` 放进服务器 `plugins/`。
3. 启动服务器生成默认配置，按需增删状态后 `/sc admin reload`。

> 部署用的是 `build/libs/statecharge-<version>.jar`；同目录的 `*-plain.jar` **不要**部署。

## 命令

别名：`/sc`

| 命令 | 权限 | 说明 |
|---|---|---|
| `/statecharge list` | `statecharge.use` | 查看可购买的状态 |
| `/statecharge status` | `statecharge.use` | 查看生效中的状态与剩余时间 |
| `/statecharge buy <状态> [份数]` | `statecharge.use` + 该状态配置的 permission（若有） | 购买，默认 1 份 |
| `/statecharge admin give <玩家> <状态> <秒数>` | `statecharge.admin.give` | 免费发放（叠加） |
| `/statecharge admin clear <玩家> [状态]` | `statecharge.admin.clear` | 清除状态，缺省清全部 |
| `/statecharge admin reload` | `statecharge.admin.reload` | 重载配置 / 语言 / 数据 |

## 权限

| 权限 | 默认 | 说明 |
|---|---|---|
| `statecharge.use` | true | 玩家命令（list / status / buy / help） |
| `statecharge.buy.<id>` | true | **仅当**某状态在配置里把 `permission` 指向该节点时才检查 |
| `statecharge.fly.keep` | false | fly 状态到期后保留飞行能力 |
| `statecharge.admin` | op | 全部管理命令（give / clear / reload） |

## 配置

```yaml
states:
  small:                          # 状态 id，用于命令与权限
    enabled: true                 # false = 不可购买（admin give 仍可用）
    display: "变小"               # 展示名，纯文本
    price: 100.0                  # 每份价格
    unit-seconds: 1800            # 每份时长（秒）
    max-stack-seconds: 21600      # 累计上限，0 = 不限
    permission: ""                # 空 = 不检查购买权限
    conflict-group: scale         # 同组互斥；显式 "" 关闭
    effect:
      type: scale                 # scale（参数 scale: 0.1..16.0）/ fly（参数 auto-start）
      scale: 0.5
```

`notifications.expiry-warning-seconds` 控制到期提醒阈值。
语言文件 `lang/zh_CN.yml` / `lang/en_US.yml`，值用 MiniMessage。

## 数据与安全

生效中的状态保存在 `plugins/StateCharge/states.yml`，按脏标记异步刷盘，并自动维护
`states.yml.bak`。文件损坏时回退到 `.bak`，避免玩家已购时长凭空消失。
**不要把 `states.yml` 当作清理手段删除**——那会丢掉所有玩家未用完的时长。

## 构建

```powershell
.\gradlew.bat :StateCharge:build      # 编译 + 测试 + 部署 jar
.\gradlew.bat :StateCharge:test       # 只跑测试
.\gradlew.bat :StateCharge:jarGate    # 部署 jar 门禁
```

Windows 必须用 PowerShell 跑 `.\gradlew.bat`（仓库路径含空格）。

## 已知边界

- 尚未公开发布。
- v1 **不含**：GUI 商店、BossBar 倒计时、PlaceholderAPI、MySQL、现实时间倒计时模式、
  bStats（需先注册服务 ID）、跨服（BungeeCord）同步。
- 新增**效果类型**（`scale`/`fly` 之外）需要改代码；新增**状态**只改配置。

## 相关文档

- 待办与路线：仓库根 [`PLAN.md`](../PLAN.md)
- 设计依据：[`DESIGN.md`](DESIGN.md)
