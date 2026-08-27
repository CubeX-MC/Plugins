# Clarity

扫描并清除已卸载插件遗留在玩家和物品上的属性、药水效果与元数据。

## 定位

一个插件被卸载后，它写进玩家 NBT 的 `AttributeModifier` 不会跟着消失。这类"幽灵修饰符"
`/effect clear` 碰不到，重置 base value 也治不了——玩家的移速、攻击力会永久停在被改过的值上。
物品同理：LevelTools 之类的插件卸载后，工具上仍留着等级 lore 与 PDC 数据。

Clarity 专门处理这一类残留：走 Bukkit Attribute API 按**服主显式点名的黑名单**扫描并移除，
不解析 NBT，不猜测哪些数据该留。

**不做什么**（避免误装）：

- 不是属性/等级系统，不会给玩家添加任何属性。
- 不是通用 NBT 编辑器，只能移除黑名单命中的 modifier、效果与已知的物品元数据。
- **绝不触碰 `minecraft` 命名空间**，原版属性不在清理范围内。

## 功能特性

- 扫描玩家身上的 attribute modifier 与药水效果，按命名空间或精确 id 匹配（大小写不敏感，支持前缀匹配）。
- 移除命中黑名单的 modifier；药水效果可限定只清"无限/超长时长"的，避免误删玩家自己喝的药水。
- 扫描与清理物品上的插件残留元数据（当前内置 LevelTools 规则），可按手持/背包/装备/末影箱/全部取范围。
- 命令支持 `@a` / `@s` / `@p` / `@r` 选择器。
- 可选的进服自动清扫：黑名单驱动，**默认关闭且默认 dry-run**。
- dry-run 模式只在控制台记录"会清什么"，不实际修改。

## 运行要求

| 项 | 要求 |
|---|---|
| 服务端 | Paper 1.21+ |
| Java | **21**（本插件用 1.21 属性 API，是全仓唯一的 Java 21 目标） |
| 必需依赖 | 无 |
| Folia | 走 EntityScheduler，Folia 安全 |

## 安装

1. 把 `Clarity-<version>.jar` 放进服务器 `plugins/`。
2. 启动服务器生成默认配置。
3. **先用 `/clarity player scan` 看清楚要清什么**，把实际 modifier id 填进 `attributes.remove-modifier-ids`，
   保持 `dry-run: true` 观察几天，确认命中无误后再关掉 dry-run。

> 部署用的是 `build/libs/Clarity-<version>.jar`；同目录的 `*-plain.jar` **不要**部署。

## 命令

别名：`/clar`、`/clarify`

| 命令 | 权限 | 说明 |
|---|---|---|
| `/clarity player scan <player\|@selector>` | `clarity.use` | 列出目标玩家身上的 modifier 与药水效果 |
| `/clarity player sweep <player\|@selector>` | `clarity.use` | 按配置的黑名单清理目标玩家 |
| `/clarity player purge <player\|@selector> attr <namespace\|id>` | `clarity.use` | 强制移除指定命名空间/id 的 modifier |
| `/clarity player purge <player\|@selector> effect <type\|all-infinite>` | `clarity.use` | 强制移除指定类型或全部无限时长的效果 |
| `/clarity item scan <player\|@selector> [范围]` | `clarity.use` | 扫描物品残留元数据 |
| `/clarity item sweep <player\|@selector> [范围]` | `clarity.use` | 按配置清理物品残留元数据 |
| `/clarity item purge <player\|@selector> [范围] leveltools` | `clarity.use` | 强制走 LevelTools 规则清理 |
| `/clarity reload` | `clarity.use` | 重新加载配置 |

物品范围可选：`hand` / `inventory` / `equipment` / `ender` / `all`。

## 权限

| 权限 | 默认 | 说明 |
|---|---|---|
| `clarity.use` | op | 使用 Clarity 的全部命令 |

## 配置

| 键 | 默认 | 说明 |
|---|---|---|
| `auto-clean-on-join` | `false` | 玩家进服时按黑名单自动检查并清理 |
| `dry-run` | `true` | 只记录"会清什么"，不实际修改 |
| `join-delay-ticks` | `40` | 进服后延迟多少 tick 再检查，给其它插件留加载时间（≥1） |
| `attributes.remove-modifier-ids` | `["adapt"]` | 命中的命名空间或精确 id 才会被清 |
| `effects.remove-types` | `[]` | 要清理的药水效果类型 |
| `effects.infinite-only` | `true` | 只清无限/超长时长效果 |
| `items.leveltools.enabled` | `true` | scan/sweep 是否检查 LevelTools 残留 |

## 构建

```powershell
.\gradlew.bat :Clarity:build      # 编译 + 测试 + 部署 jar
.\gradlew.bat :Clarity:test       # 只跑测试
.\gradlew.bat :Clarity:jarGate    # 部署 jar 门禁
```

Windows 必须用 PowerShell 跑 `.\gradlew.bat`（仓库路径含空格）。
产物在 `Clarity/build/libs/`；`*-plain.jar` **不要**部署。

## 已知边界

- 只能清理**已加载**的玩家与其物品；离线玩家需要等其上线后处理。
- 黑名单是显式的：没填进配置的残留不会被动到。这是有意的安全默认，不是缺陷。
- 只走 Bukkit Attribute API，不解析也不改写原始 NBT。

## 相关文档

- 待办与路线：仓库根 [`PLAN.md`](../PLAN.md)
- 发布检查：[`docs/release-checklist.md`](docs/release-checklist.md)
