# CubeX 命令与权限规范

本文是新增命令、子命令和权限节点的统一规范。命令的实现与 `plugin.yml` 是事实来源；
插件 README 的命令/权限表和语言文件必须与它们同步。

适用范围：所有新插件、现有插件新增的命令面，以及待首个正式 release 的插件主动调整的命令面。
已有正式 release 的插件保留既有节点，除非随大版本提供兼容期；不要只为格式统一而静默改名。

## 1. 命令语法

- 根命令、子命令和固定参数使用小写 ASCII：`/regions game arena ready`。
- 根命令必须支持 `/<root> help`。无参数时可以打开主 GUI，也可以显示帮助，但 README 和
  `plugin.yml` 的 `usage` 必须与实际行为一致。
- 用 `<required>` 表示必填参数、`[optional]` 表示可选参数、`a|b` 表示互斥枚举；
  可重复参数写成 `<value...>`。不要用省略号掩盖实际参数顺序。
- 语言文件使用 MiniMessage 时，帮助正文中的尖括号必须转义，例如 `\<player>`；
  传给 `<usage>` 占位符的动态文本必须按普通文本处理，不能被再次当作标签解析。
- 未知子命令、参数不足和参数格式错误必须返回本地化的具体 usage；不要依赖 Bukkit 自动发送
  `plugin.yml` 的整段 usage，也不要同时发送两份错误。
- 玩家专属命令由控制台调用时返回本地化的 `player-only`，不能抛类型转换异常。

## 2. 权限节点

权限前缀使用插件面向服主的稳定小写 id。根命令别名不产生新的权限前缀。

| 类型 | 格式 | 示例 |
|---|---|---|
| 普通聚合节点 | `<plugin>.use` | `contract.use` |
| 管理聚合节点 | `<plugin>.admin` | `statecharge.admin` |
| 可执行能力 | `<plugin>.<area>.<action>` | `regions.region.publish` |
| 绕过规则 | `<plugin>.bypass.<capability>` | `contract.bypass.fee` |
| 配置出的玩法能力 | `<plugin>.<area>.<value>` | `statecharge.state.fly` |

规则如下：

1. 新的可执行叶节点必须表达“对象域 + 动作”，例如 `plugin.template.publish`、
   `plugin.config.reload`、`plugin.session.inspect`。不要新增含义模糊的 `plugin.manage` 或
   `plugin.admin.do`。
2. `.use` 和 `.admin` 是给服主批量授权的聚合节点，不代替叶节点。聚合关系写在
   `plugin.yml` 的 `children`；执行、帮助和补全检查同一个叶节点。
3. 可单独委派的管理动作必须有独立叶节点。拥有 `plugin.admin` 的玩家通过 children 获得它，
   服主也可以只授予 `plugin.config.reload`。
4. `bypass` 只表示绕过既有约束，不能承载普通管理动作。危险节点默认 `op` 或 `false`；
   普通玩家安全能力才默认 `true`。
5. 所有代码中检查的静态节点都必须在 `plugin.yml` 声明；配置项允许填写自定义节点时，
   README 要写清检查时机和空值语义。
6. 控制台是否绕过权限必须在命令入口显式决定。不要让“不是 Player”意外等于管理员。
7. 权限常量集中在命令类的 companion/object 或单一权限对象中；同一个字面量不要散落在
   命令、GUI 和 service 多处。

现有的 `contract.accept`、`regions.reload`、`clarity.use` 等首发前节点早于本规范，不能作为
新增节点的命名范例。待首个正式 release 的插件若要迁移它们，应在一个原子变更里同时修改实现、
`plugin.yml`、README、语言帮助与测试；已有正式 release 的插件则遵守兼容纪律，不做无过渡期改名。

## 3. Help、usage 与补全

- help 只展示发送者当前可执行的命令；不能向普通玩家列出无权限的管理命令。
- tab completion 使用与执行入口相同的权限判定和参数枚举。根补全统一走
  `CubexCommandSuggestions.root(...)`，在完成根参数形态门禁前不要读取 `args[0]`。
- 每条帮助至少包含可复制的完整语法和一句结果说明。高风险动作还要说明影响对象，
  不能只写“管理命令”。
- `plugin.yml` 只保留简短的根 usage；完整、可翻译的帮助放进语言文件。
- README 的命令表列公开语法、实际检查的权限叶节点和行为。别名只在 README 集中说明一次。

## 4. 消息与颜色

所有玩家可见的命令消息必须来自语言文件并使用 MiniMessage。日志中的开发诊断可以留在代码，
但异常堆栈、内部类名和路径不能直接发给玩家。

| 语义 | 颜色 |
|---|---|
| 成功、可执行命令 | `<#69DB7C>` |
| 错误、拒绝、危险结果 | `<#E63946>` |
| 警告、usage、待确认 | `<#FFE066>` |
| 正文、说明 | `<#CFD8DC>` |
| 主要值 | `<#FFFFFF>` |
| 次要元数据 | `<#90A4AE>` |

- 每个插件统一使用自己的 `<prefix>`，同一条反馈只加一次。
- 错误消息应说明失败原因；用户能修复时再给下一步。不要只回复 `Error`、`Invalid` 或
  `No permission`。
- 成功色表达操作成功，不表达动作本身是否危险；例如“已删除”可以用成功色，确认删除的按钮
  才使用危险色。
- 列表使用稳定的 header/line/empty 三组键；分页页码从 1 开始，与
  `cubex-gui` 的 `Pagination` 一致。

## 5. 变更检查表

新增或修改命令时逐项确认：

- [ ] 命令实现、`plugin.yml`、所有语言文件和 README 同步。
- [ ] 执行、help、GUI 入口和 tab completion 使用相同权限。
- [ ] 新叶节点符合 `<plugin>.<area>.<action>`，聚合节点用 children 授权。
- [ ] 无权限、玩家专属、未知参数、参数错误和成功路径都有且只发送一条反馈。
- [ ] 帮助中的 `<required>` 已在 MiniMessage 资源中正确转义。
- [ ] 至少用测试锁住权限过滤和一个非法 usage；动态命令另测 reload/disable 后注销。
- [ ] 若改动已有正式 release 的权限，提供旧节点兼容期和迁移说明；否则不改名。
