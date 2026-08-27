# CubeX 插件 README 模板

> 所有 `<Plugin>/README.md` 按本模板组织。README 是**面向服主的唯一说明书**：
> 它描述**当前已实现**的行为，不描述计划。计划一律写进仓库根 [`PLAN.md`](PLAN.md)。

## 使用规则

1. **只写已实现的功能。** 未实现的类型/命令/集成不得出现在功能表里；
   确有必要提及时，放进「不做什么 / 已知边界」并明确标注未实现。
2. **章节顺序固定**，缺内容就整节删掉，不要留空标题，也不要调换顺序。
3. **命令、权限、配置三张表以 `plugin.yml` 和默认 `config.yml` 为准**，改代码时同步改表；
   新节点和提示遵守 [`COMMAND_PERMISSION_GUIDE.md`](COMMAND_PERMISSION_GUIDE.md)。
4. 有 `README_en.md` 的插件，两份保持章节结构一致。
5. 不在 README 里写实现细节与设计理由——那些属于 `DESIGN.md`。

---

## 模板

```markdown
# <PluginName>

<!-- 已在 bStats 注册的插件放徽章，未注册的删掉这一行 -->
![](https://bstats.org/signatures/bukkit/<PluginName>.svg)

<一句话说明这个插件是什么。不超过两行。>

## 定位

<2-4 段。回答三个问题：解决什么问题、为谁解决、和同类方案的差异在哪。
这是 README 里唯一允许讲"为什么"的地方，写给正在决定要不要装的服主看。>

**不做什么**（避免误装）：

- <明确排除的方向，例如"不是 XX 系统"、"不替代 XX 插件">

## 功能特性

- <逐条列已实现能力，动词开头，一条一个能力>

## 运行要求

| 项 | 要求 |
|---|---|
| 服务端 | <Paper 1.x / Spigot 1.x；写清最低版本> |
| Java | <17 / 21> |
| 必需依赖 | <Vault 等；没有就写"无"> |
| 可选依赖 | <PlaceholderAPI / Lands / …；没有就删掉这一行> |
| Folia | <支持 / 不支持> |

## 安装

1. 把 `<plugin>-<version>.jar` 放进服务器 `plugins/`。
2. <必需依赖的安装说明；没有依赖就删掉这一步>
3. 启动服务器生成默认配置，按需修改后 `/<cmd> reload`。

> 部署用的是 `build/libs/<plugin>-<version>.jar`；同目录的 `*-plain.jar` **不要**部署。

## 命令

别名：`/<alias>`

| 命令 | 权限 | 说明 |
|---|---|---|
| `/<cmd> help` | <permission> | <说明> |

## 权限

| 权限 | 默认 | 说明 |
|---|---|---|
| `<plugin>.<area>.<action>` | <op / true / false> | <说明> |

## 配置

<只讲服主真正需要调的项，不要逐字复制 config.yml。>

| 键 | 默认 | 说明 |
|---|---|---|

## 数据与安全

<有持久化数据的插件必写：存在哪、什么格式、什么时候落盘、崩溃/reload 如何恢复、
哪些文件删不得。没有持久化数据的插件删掉整节。>

## 构建

```powershell
.\gradlew.bat :<Plugin>:build      # 编译 + 测试 + 部署 jar
.\gradlew.bat :<Plugin>:test       # 只跑测试
.\gradlew.bat :<Plugin>:jarGate    # 部署 jar 门禁
```

Windows 必须用 PowerShell 跑 `.\gradlew.bat`（仓库路径含空格）。

## 已知边界

- <明确的能力边界与未覆盖场景，让服主不会误期待>

## 相关文档

- 待办与路线：仓库根 [`PLAN.md`](../PLAN.md)
- 设计依据：[`DESIGN.md`](DESIGN.md) <没有就删>
- 版本记录：[`CHANGELOG.md`](CHANGELOG.md) <没有就删>
```
