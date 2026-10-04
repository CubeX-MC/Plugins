# ALLIANCE 终态结算 · CT-A01 · 2026-10-01

## 开工与设计

- 阶段：根 PLAN §5.1 CT-A01；R3（资金、持久化与恢复）。起点分支 main，Contract 工作树无改动。
- 保留已有根 PLAN / Regions PLAN 更新及其他插件工作树；本轮不提交、不推送。
- 只接 service，不开放 ALLIANCE 玩家创建/GUI；真实 Paper/Folia/Vault 余额验收另留待办。
- 全员签署后按 UUID 审批；未全签取消/到期只退已注资本金；全签取消进入争议，管理员可退款或具名违约裁决。
- 在付款前把完整 source/recipient UUID 分配、签署快照及逐收款人 READY/PAYING/PAID 状态原子保存到 pending journal，合同保存操作 ID。
- READY 可安全继续；PAID 不重付；PAYING 的 Vault 结果不确定，保留记录并阻止任何二次结算、注资、管理员关闭及清理。
- journal 损坏/身份不符/合同缺失均失败关闭。旧 role-based executor 不处理 ALLIANCE，旧类型格式不增加必填字段。
- 验证：真实临时 YAML reload、审批/退款/尾差、未决注资与所有日志/付款/终态保存中断；Contract build + jarGate。

## 验证结果

PowerShell，仓库根目录：

```powershell
.\gradlew.bat :Contract:test --tests 'org.cubexmc.contract.service.Alliance*' --tests 'org.cubexmc.contract.storage.Alliance*' --tests 'org.cubexmc.contract.storage.PendingTransactionStoreTest' --tests 'org.cubexmc.contract.model.AllianceTest' --tests 'org.cubexmc.contract.config.ContractsMigrationTest' --tests 'org.cubexmc.contract.config.LanguageParityTest' --console=plain
.\gradlew.bat :Contract:clean :Contract:build :Contract:jarGate --console=plain
git diff --check -- Contract PLAN.md
```

- 定向：8 个测试类、87 项，失败/错误/跳过均为 0。
- 完整 Contract 门禁：36 个测试类、213 项，失败/错误/跳过均为 0；BUILD SUCCESSFUL。
- jarGate：EMBEDDED，unrelocatedKotlin=0、relocatedKotlin=1029、reflectImpl=0、
  relocatedModuleEntries=123、ownClasses=282、pluginBytecodeMajors=[61]、sharedBytecodeMajor=61。
- 产物：`Contract/build/libs/contract-0.1.0.jar`；SHA256
  `047D50B270E60E857E8F54AF566256FBE3268A5ED1CA7A0C9F55E288DC1BE758`。
- 未安装、未启动实服、未提交或推送；没有改工具链、平台版本、依赖或发布状态。
- 初次沙箱执行因 Gradle 网络/缓存权限失败，扩大权限后构建正常；没有审批拒绝。
- 新测试初次编译遇到 Java 通配符 Map 写入错误，修正为明确的 Map 副本。
  首轮执行三个测试在 Mockito `when` 重配置时调用旧 Answer，随后用 `doAnswer` 修正测试桩；
  重跑 48 项通过，再补完整 journal 验证与签署时间数值类型兼容后定向 87 项和全量门禁均通过。
- 既有 Gradle 9 弃用、vendored Metrics API 弃用、JVM CDS 以及 Git LF/CRLF 提示仍在；无空白错误。

## 自动化覆盖与执行协议

- 全员签署前不审批；成员权限、非成员、旧对象、重复/并发最后审批均不会越权或二次付款。
- 审批先写严格审计再同步保存；最后审批保存成功但准备 journal 失败时，可重试准备。
- 部分签署取消/超时只退已注资成员，全签截止日不自动结算；全签取消转争议，再由管理员退款。
- 具名裁决需 `contract.admin.settle` 与全签争议状态；实际 deposit 按 UUID 汇总，含 10.01 元奇数分尾差验证。
- 新 journal 与旧 unphased/phased 记录共存；缺失版本、结果、发起人、本金、签署快照、分配或付款状态时拒读。
  旧九/十参数 PendingEntry 构造器保留；小时间戳在 YAML Integer/Long 之间转换后仍验证一致。
- 新计划包含完整本金条款、签署/审批快照、来源/收款 UUID、发起人、结果与逐收款人阶段；
  存档重读时重新计算并核对原计划。分配/条款被改、缺合同、操作 ID 冲突均保留人工核账。
- 准备计划后，先把操作 ID 保存到合同。此锚点阻止丢失 journal 后创建另一份付款计划。
- 每笔先严格写 `ALLIANCE_PAYOUT_INTENT`，再把 READY 原子保存成 PAYING，然后调用 Vault；
  明确成功后原子保存 PAID。任何 PAYING 都停止执行，不重试未知结果，包括失败回复后实际已入账的场景。
- 所有 PAID 后先写终态审计，再保存合同终态，最后清 journal。清日志失败仍返回已完成结果，
  重启只清匹配的终态记录；终态保存失败恢复内存状态，重启仅完成保存，不再次付款。

| 故障窗口 | 磁盘证据 / 自动恢复结果 |
|---|---|
| 审批审计或合同保存失败 | 不留下内存假审批；未付款 |
| 准备 journal 失败 | 全员审批可重试准备；未付款 |
| 合同锚点审计/保存失败 | 保留 READY 计划；重启验证后可锚定继续 |
| 付款审计或 READY→PAYING 保存失败 | 未调用 Vault；重启继续未执行付款 |
| PAYING 落盘后、Vault 调用前崩溃 | 无法从磁盘确定外部执行结果；保留核对，不付款 |
| Vault 失败回复、异常、入账后崩溃 | 保留 PAYING；重启与其他路径不重付 |
| Vault 成功、PAID 确认保存失败 | 已入账仍保留 PAYING；不重付 |
| PAID 已落盘后崩溃 | 跳过已确认收款人，继续 READY，余额与剩余本金守恒 |
| 终态审计/合同保存失败 | 全部 PAID；重启只完成终态，不重付 |
| 清日志失败 / 终态已保存后重启 | 核对合同锚点、终态与全部 PAID 后仅清日志 |
| 未决 funding / 竞争 intent | 先恢复 funding；未决时不执行付款或清理 |

## 文件与兼容范围

- 新增 `AllianceSettlement`、`AllianceSettlementService`、lang v6→v7 迁移与两组故障/存储测试。
- `ContractService` 负责同步分派、恢复顺序及管理员/清理门禁；funding service 补结算锁门禁。
- `PendingTransactionStore` 复用严格读取与同目录原子替换，新增可选 `alliance-settlement` v1；
  `EventLog.appendRequired` 为该资金链路提供失败关闭，旧 `append` 保留 best-effort 行为。
- `AlliancePayoutPlan` 保留原计算/状态前置；内部本金计算支持终态存档的原计划复核，不新增 SourceSelector。
- README、CHANGELOG、profile、release checklist、两份历史 alliance evidence 与根 PLAN 同步。
- 合同 `alliance.version: 1` 不变，仅增加 settlement operation metadata；旧四类型没有新增必填字段。

## 真人、回滚与交接

- 未覆盖真实 Paper/Folia/Vault provider 的余额、线程、多区域或崩溃响应语义，仍按 CT-V02/A02 验收。
- 单测使用真实临时 YAML reload、模拟存储异常与 JVM 中断边界，不证明断电耐久性或真实经济系统 exactly-once。
- 没有联盟创建费/结算佣金，没有新命令/GUI、人工解锁或自动核账工具。
- 含新结算计划时不降级旧恢复实现；保全 contract、pending、events.log 和 provider 流水，
  按操作 ID、来源本金、收款 UUID 与阶段核账，不能直接删日志解锁。
- CT-A01 自动化范围完成；下一可执行代码切片是根 PLAN 的 CT-A02 玩家确认与多成员展示，
  实服余额与多人验收保持未完成。本文件是证据，根 PLAN 继续作为唯一进度清单。
