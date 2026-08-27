# ALLIANCE 注资与恢复验证 · 2026-08-27

## Task / starting state

- 请求：继续推进根 PLAN，真人测试继续暂缓。
- 阶段：PLAN §5.1 的逐成员注资 service 与失败补偿；风险 R3（经济、存档和恢复）。
- 分支：`feat/cubexlib-and-shared-capabilities`。
- 起点：已有未提交的 SALE、ALLIANCE 模型及文档 WIP；本轮保留它们，不提交、不推送。
- 本轮未新增玩家创建命令/GUI，也没有把动态本金计划接成终态付款循环。

## 实现与边界

- `service/AllianceFundingService.kt`：在 `ContractService` 的同一监视器下处理创建者与盟友注资，
  验证权限、金额精度/范围、人数/UUID 唯一性、期限、文本长度和合同限额。
- `service/ContractService.kt`：新增 service-only `createAlliance`、ALLIANCE accept 分派；
  分阶段注资恢复不再落入旧的“只看合同总状态”恢复分支。
- `storage/PendingTransactionStore.kt`：新增 `funding-phase` 和带前置阶段检查的转换。
  共享日志严格读取、同步读改写、同目录临时文件原子替换；不原地截断，不恢复旧日志备份。
  该小范围可靠性修改是新旧交易共用日志文件所必需的，旧记录字段与九参数构造器保持兼容。
- `config/LangV5ToV6Step.kt`、bootstrap 和双语文件：lang v6 补资金失败/核对提示，保留服主措辞。
  失败理由包含必要的操作 ID，不向玩家透出内部异常路径。
- 新增 `AllianceFundingServiceTest`；扩展 pending、语言迁移和语言键覆盖测试。
- PLAN、profile、README、CHANGELOG、release checklist 同步实现边界及降级警告。

## 资金不变量

- 每次注资使用独立 pending 操作 ID。合同签署与 `metadata.alliance-funding-op-<uuid>` 同次保存。
- 邀请不是已注资；只有最后一人落盘后才进入 `IN_PROGRESS`，`accepted-at` 为最终签署时间。
- 重复/并发签署同一成员不会重复扣款；旧合同对象、已到期邀请、非受邀玩家均不能注资。
- 部分已注资的盟友占用接受合同名额；尚未签署的邀请不占用。沿用现有创建/接受权限和限额配置。
- 保存失败恢复签署、全局状态、接受时间与操作 ID，再按分阶段补偿退款；不会留下假签署。
- `createAlliance` 每次调用表示新建一个合同，不把同标题/同成员误合并为同一合同；
  未来创建确认入口仍须沿用一次确认/防重复消费机制。

| 日志阶段 | 已知事实 | 自动恢复行为 |
|---|---|---|
| PREPARED | 已准备扣款，但 Vault 是否执行尚不确定 | 保留人工核对，不自动退款或再次扣款 |
| WITHDRAWN | Vault 明确扣款成功 | 匹配 UUID、金额、操作 ID 的签署已落盘则仅清日志；确认未提交则补偿退款 |
| REFUNDING | 已准备补偿，但 Vault 是否退款尚不确定 | 保留人工核对，不再次付款 |
| REFUNDED | Vault 明确退款成功 | 仅清日志，不重复退款 |
| REJECTED | Vault 明确拒绝扣款 | 仅清日志，不退款 |

签署/操作冲突、缺失的 accept 合同、金额不匹配都保留核对。未决记录阻止该玩家或同合同的后续注资。
确定的退款拒绝可以回到 WITHDRAWN 后重试；退款响应异常或确认日志失败则保持 REFUNDING。
Vault 没有事务幂等保证，因此这里有意保留人工核对窗口，而不声称所有中断都能自动完成。

## 自动化证据

在仓库根目录、PowerShell 执行：

```powershell
.\gradlew.bat :Contract:test --tests "org.cubexmc.contract.service.AllianceFundingServiceTest" --tests "org.cubexmc.contract.service.ContractServiceRecoveryTest" --tests "org.cubexmc.contract.storage.PendingTransactionStoreTest" --tests "org.cubexmc.contract.config.ContractsMigrationTest" --tests "org.cubexmc.contract.config.LanguageParityTest" --no-daemon
.\gradlew.bat :Contract:clean :Contract:build :Contract:jarGate --no-daemon
git diff --check -- Contract PLAN.md
```

- 首轮定向 41 项中，1 项金额精度错误的异常类型不一致；统一为参数异常后重跑，41 项全部通过。
- 随后追加退款前日志失败、退款异常和损坏日志阻止注资三项测试，纳入完整门禁。
- 完整门禁：`BUILD SUCCESSFUL`；33 个测试类、172 项测试，失败/错误/跳过均为 0。
- `jarGate`：`mode=EMBEDDED`、`unrelocatedKotlin=0`、`relocatedKotlin=1029`、
  `reflectImpl=0`、`cubexModuleEntries=121`、`ownClasses=270`、
  `pluginBytecodeMajors=[61]`、`sharedBytecodeMajor=61`。
- 部署构建产物：`Contract/build/libs/contract-0.1.0.jar`；未安装到实服。
- 定向/全量测试使用临时真实 YAML 文件、存储 reload、模拟 Vault 返回值和异常、并发签署及存档失败注入。
  覆盖旧 unphased journal、拒绝/退款成功后清日志失败、已提交部分签署重载、操作 ID 冲突、孤立 create 与缺失 accept。
- `git diff --check` 无空白错误；Git 仍提示 LF/CRLF。编译保留既有 API 弃用、JVM CDS 和 Gradle 9 弃用提示，
  未据此改变平台或工具链版本。

## 真人验证 / 迁移 / 回滚

- 按用户要求未启动 Paper/Folia/Vault 实服；模拟测试不能证明具体经济 provider 的响应语义或线程行为。
- 合同保存新增可选成员操作 ID metadata；签署数据仍为 `alliance.version: 1`。
- ALLIANCE pending WITHDRAW 新增 `funding-phase`，旧类型记录不增加必填字段。
- 有联盟合同或分阶段 pending 记录时禁止降级到旧恢复实现；旧版可能忽略阶段并错误退款。
- 日志 YAML 损坏时拒绝继续读改写；不支持原子替换的文件系统会让写入失败，而不是退回原地覆盖。
- PREPARED/REFUNDING 等待核对时，应保存合同、pending 和经济插件交易记录，按操作 ID 核查，
  不直接删除日志解锁。本轮未新增人工解锁命令。
- 不宣称断电耐久性、网络文件系统原子移动或真实经济系统的 exactly-once；这些不由本地单测证明。

## 后续交接

根 PLAN §5.1 的下一切片是终态结算 service：审批完成、取消/超时与具名违约结果需要持久化
UUID 付款计划、执行状态及人工核对锁。任何未决注资必须阻止结算与清理；完成后才开放玩家创建。
本记录不是第二份待办计划，当前顺序和完成状态均以根 PLAN 为准。
