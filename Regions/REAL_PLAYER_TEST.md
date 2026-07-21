# Regions 真人验证清单

本文用于首个公开版本前的真人验收。测试服使用 Java 21、Paper 1.21.11，并安装与正式服一致版本的 Lands、RuleGems 及其依赖。开始前备份世界、玩家数据和 `plugins/Regions`。

## 1. 测试角色

- `ruler_owner`：拥有 `regions.admin`，同时是目标 Lands 区域主人。
- `ruler_not_owner`：拥有 `regions.admin`，但不是目标区域主人。
- `owner_not_ruler`：是目标区域主人，但没有 `regions.admin`。
- `ordinary_player`：普通参与者。
- `superadmin`：拥有 `regions.superadmin`，用于事故恢复，不参与日常场地管理。

准入标准：只有 `ruler_owner` 能日常创建、编辑、试运行和发布自己的 Region；另外两种半授权身份都必须被拒绝；`superadmin` 的接管操作必须留下审计记录。

## 2. 创建与发布

1. `ruler_owner` 使用 `/regions gui` 从自己的 Lands area 创建场地。
2. 应用一个内置模板，修改当前 Mode 的专用设置、一个 Flag 和一个 Effect。
3. 打开发布预览，确认能看到 revision diff、依赖、警告/错误、最终 Mode、主 Trigger Region、最终 Flag 和 Effect。
4. 故意填入无效物品、人数或位置，确认发布被阻止且错误能指出修改方向。
5. 修正后发布；普通玩家进入区域时只能运行已发布 revision。
6. 再修改草稿，确认未重新发布前不会影响正在运行的场地。
7. 撤回、再次发布、查看历史并回滚；确认回滚生成新 revision，而不是覆盖旧历史。

通过标准：整个流程无需直接编辑 YAML；重启后草稿、已发布 revision 和历史保持一致。

## 3. 权限与所有权

分别用三个非超级管理员角色尝试打开 GUI、编辑、试运行、发布和开赛：

- `ruler_owner`：允许。
- `ruler_not_owner`：拒绝，提示不是当前来源主人。
- `owner_not_ruler`：拒绝，提示只有 RuleGems 统治者可管理。

随后转让 Lands 所有权：

1. 旧主人再次编辑或开赛时必须立即失去权限。
2. Region 应冻结，不删除历史。
3. 新主人不能直接继承旧主人的未发布草稿。
4. `superadmin` 复核、解冻或处理后，审计中可看到操作者、原因和 Region。

## 4. 隔离试运行

1. `ruler_owner` 对草稿执行 `/regions trial <id>`。
2. 只有本人受到草稿 Flag 与 Effect 影响；附近其他玩家不受影响。
3. Mode 不应开赛，Trigger 不应向其他玩家或控制台执行。
4. 执行 `/regions trial <id> stop` 后立即恢复。
5. 分别在试运行中测试死亡、断线、reload 和插件关闭。

通过标准：所有退出路径均无 scale、速度、飞行、药水或其他临时状态残留；效果应用失败时整次试运行回滚。

## 5. 玩家状态恢复

测试前记录玩家原始飞行状态、速度、scale、药水效果和装备：

- 正常进入/离开。
- 区域内死亡与重生。
- 区域内断线并重连。
- `/regions reload`。
- Lands reload/短暂不可用。
- 正常停服并重启。
- 可控测试环境中的异常进程终止与恢复。

通过标准：Regions 只恢复自己持有 lease 的状态，不覆盖其他插件或玩家原本合法状态；`/regions inspect <玩家>` 与 `/regions cleanup <玩家>` 可用于诊断和恢复。

## 6. 重叠区域

创建两个有交集的 Cuboid 或可提供 geometry 的 Source 区域：

1. 设置不同 priority 和冲突 Flag，确认预览与运行时选择同一来源。
2. 测试 `HIGHEST_PRIORITY`、`EXCLUSIVE`、`STACK`、`MERGE_BY_TYPE` Effect。
3. 测试 `PRIMARY_REGION` Trigger 只由主 Region 执行。
4. 尝试发布两个重叠的有状态 Mode，确认发布被阻止。
5. 离开其中一个重叠区域，确认剩余效果重新解析且原始状态最终可恢复。

## 7. 多人 Mode

至少完成以下各一轮：

- `dual_pvp`：报名/确认、装备托管、开始、死亡/退出、结束、恢复装备。
- `union_war`：同工会/敌对工会判断、最低人数、强制结束。
- `run_race` 或载具赛：起点、检查点、终点、名次、超时。
- `hide_and_seek`：角色分配、躲藏计时、找到玩家、回合结束。

通过标准：Mode 的开始/结束、参与者、结果、revision 和强制操作均进入审计；中途退出或管理员终止不会复制或吞掉装备。

## 8. Paper 与 Folia

先在 Paper 完成全部流程，再在真实 Folia 重复以下关键项：

- GUI 创建、预览、发布。
- 玩家进入/离开与死亡/断线清理。
- 隔离试运行。
- 至少一个战斗 Mode 和一个计时 Mode。
- reload、正常停服和重启。

任何线程违规、Region scheduler 异常、未清理 lease、装备恢复失败或 Regions 栈追踪都视为阻断问题。

## 9. 记录方式

每个失败至少记录：

- 复现角色、Region id、Source 和 revision。
- 操作步骤、预期结果、实际结果。
- 服务端时间和相关日志。
- `/regions inspect <玩家>`、`/regions validate <id>`、`/regions preview <id>` 的输出。
- 是否可通过 `/regions cleanup <玩家>` 或 superadmin 操作恢复。

只有所有阻断问题修复并回归后，才进入首个公开版本打包。
