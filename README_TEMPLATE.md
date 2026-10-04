# CubeX 插件 README 模板

> 所有 `<Plugin>/README.md` 按本模板组织。README 是**面向服主的唯一说明书**：
> 它描述**当前已实现**的行为，不描述计划。计划一律写进仓库根 [`PLAN.md`](PLAN.md)。

## 使用规则

1. **只写已实现的功能。** 未实现的类型/命令/集成不得出现在功能表里；
   确有必要提及时，放进「不做什么 / 已知边界」并明确标注未实现。
2. **章节顺序固定**，缺内容就整节删掉，不要留空标题，也不要调换顺序。
3. **命令、权限、配置三张表以 `plugin.yml` 和默认 `config.yml` 为准**，改代码时同步改表；
   新节点和提示遵守 [`COMMAND_PERMISSION_GUIDE.md`](COMMAND_PERMISSION_GUIDE.md)。
4. 有 `README_en.md` 的插件，两份保持章节结构一致（头部徽章与链接也要对应）。
5. 不在 README 里写实现细节与设计理由——那些属于 `DESIGN.md`。
6. **头部统一为居中卡片**（见下方模板开头）：`<div align="center">` 内依次放 logo（112px，图片放
   `<Plugin>/img/` 用相对路径）、`<h1>`、一句话副标题、徽章行、快捷链接行，bStats 签名图也在其中；
   徽章只放有真实来源的（Stars/Forks/Issues 需要 GitHub 仓库，CI 需要对应工作流，Release 需要有已发布
   版本，Folia 需要 README 明确声明支持），链接行按实际拥有的入口增删。

---

## 模板

```markdown
<div align="center">
  <!-- 有 logo 才放这一行，无 logo 删掉。图片放 <Plugin>/img/，用相对路径引用 -->
  <img src="img/<logo 文件名>" width="112" alt="<PluginName> Logo">
  <h1><PluginName></h1>
  <p><一句话副标题，不带句号></p>
  <p>
    <!-- Stars / Forks / Issues：有 GitHub 仓库的插件放；仓库名按实际替换（例：FAWEReplace） -->
    <a href="https://github.com/CubeX-MC/<PluginName>"><img src="https://img.shields.io/github/stars/CubeX-MC/<PluginName>?style=flat-square&logo=github&label=Stars" alt="GitHub Stars"></a>
    <a href="https://github.com/CubeX-MC/<PluginName>/network/members"><img src="https://img.shields.io/github/forks/CubeX-MC/<PluginName>?style=flat-square&logo=github&label=Forks" alt="GitHub Forks"></a>
    <a href="https://github.com/CubeX-MC/<PluginName>/issues"><img src="https://img.shields.io/github/issues/CubeX-MC/<PluginName>?style=flat-square&label=Issues" alt="GitHub Issues"></a>
    <!-- Java / 服务端：按真实要求改数字；Spigot/Paper 混合写法为 Spigot%20%2F%20Paper-1.x%2B -->
    <img src="https://img.shields.io/badge/Java-17%2B-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Java 17+">
    <img src="https://img.shields.io/badge/Paper-1.18%2B-5D8AA8?style=flat-square" alt="Paper 1.18+">
    <!-- 以下三行按需保留：Folia 支持 / 仓库有 CI 工作流 / 已有 Release -->
    <!--<img src="https://img.shields.io/badge/Folia-supported-brightgreen?style=flat-square" alt="Folia">-->
    <!--<a href="https://github.com/CubeX-MC/<PluginName>/actions/workflows/ci.yml"><img src="https://github.com/CubeX-MC/<PluginName>/actions/workflows/ci.yml/badge.svg" alt="CI"></a>-->
    <!--<a href="https://github.com/CubeX-MC/<PluginName>/releases"><img src="https://img.shields.io/github/v/release/CubeX-MC/<PluginName>?style=flat-square&label=Release" alt="Release"></a>-->
  </p>
  <p>
    <!-- 快捷链接行：按实际入口增删（语言切换 / Wiki / Discord / QQ / Modrinth / 项目主页 / 问题反馈） -->
    <a href="README_en.md">English</a>
    ·
    <a href="https://github.com/CubeX-MC/<PluginName>">项目主页</a>
    ·
    <a href="https://github.com/CubeX-MC/<PluginName>/issues">问题反馈</a>
  </p>
  <p>
    <!-- 已在 bStats 注册的插件放签名图；未注册删掉这个 <p> 块 -->
    <img src="https://bstats.org/signatures/bukkit/<PluginName>.svg" alt="bStats">
  </p>
</div>

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
