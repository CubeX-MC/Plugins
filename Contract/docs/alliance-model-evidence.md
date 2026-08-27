# ALLIANCE 底层验证记录 · 2026-08-27

> 本文保留模型切片完成时的验证结果；后续注资 service 的实现与证据见
> [`alliance-funding-evidence.md`](alliance-funding-evidence.md)，当前待办仍以根 PLAN 为准。

## Task / starting state

- 请求：继续推进根 `PLAN.md`，暂缓真人测试。
- 阶段：PLAN §5.1 ALLIANCE 的模型、签署存档、动态本金分配切片；风险 R3（存档/资金模型）。
- 分支：`feat/cubexlib-and-shared-capabilities`。
- 起点：Contract 与 PLAN 已有未提交的 SALE、escrow 与发布文档改动，均保留。
- 本轮不提交、不推送、不启动服务器；未增加玩家命令或开放 ALLIANCE GUI 创建。

## 实现与文件范围

- `model/Contract.kt`、`ContractStatus.kt`、`ResolutionRule.kt`、`ContractType.kt`：
  `createAlliance`、`PENDING_ACCEPT_MULTI`、`ALL_APPROVE` 与当前能力边界。
- `model/AllianceAgreement.kt`：不可变 UUID 签署/审批快照；重复操作不改写原签署时间。
  签署代表已经注资，而非邀请。服务层仍负责真实扣款、落盘和合同状态迁移。
- `model/AlliancePayoutPlan.kt`：从已签署集合动态生成明确的 source/recipient UUID 金额分配。
  不增加 `SourceSelector`，不使用 `participant(ALLY)` 选择收款人；不执行 Vault 调用。
- `storage/ContractStorage.kt`：可选 `alliance` v1 数据块的读写与异常签署加载保护。
- `gui/ContractGui.kt`、`ContractRenderer.kt`：新状态显示，不增加可执行的创建/签署按钮。
- `config/LangV4ToV5Step.kt`、`ContractPlugin.kt`、两份语言文件：迁移到 lang v5，保留自定义措辞。
- 新增 `AllianceTest` / `AllianceStorageTest`；扩展状态、迁移和语言键检查测试。
- 根 PLAN、README、CHANGELOG、project profile、release checklist 同步当前边界与回滚警告。

## 不变量

- 至少三名 UUID 唯一的成员，恰好一名 OWNER，其余为 ALLY；每项本金都是正数、精确到分的 MONEY。
- 创建时复制成员草稿；只有创建者初始签署，未接受的盟友条款不被误算为已托管资金。
- 签署时间不得早于创建或达到接受截止时间；全员签署后才能记录审批。
- 退款只返还已注资成员；成功要求全员签署及审批；违约计算要求已全员签署的争议状态。
- 守约者先取回自己本金，违约者本金按 N−1 均分。整数分除法及 UUID 排序尾差保证逐来源守恒。
- 该计划只含本金；手续费策略、真实付款、结算日志、权限与裁决授权不由纯模型决定。
- 签署数据缺失/损坏会阻止加载，保留原内存库；不静默丢弃合同，也不自动选取缺少签署的旧快照。
- 旧 SERVICE/WAGER/PARTNERSHIP/SALE 不新增必填字段。没有改变编译、平台或依赖版本。

## 自动化证据

从仓库根目录使用 PowerShell：

```powershell
.\gradlew.bat :Contract:test --tests "org.cubexmc.contract.model.AllianceTest" --tests "org.cubexmc.contract.model.ContractStatusTest" --tests "org.cubexmc.contract.storage.AllianceStorageTest" --tests "org.cubexmc.contract.config.ContractsMigrationTest" --tests "org.cubexmc.contract.config.LanguageParityTest" --no-daemon
.\gradlew.bat :Contract:clean :Contract:build :Contract:jarGate --no-daemon
git diff --check -- Contract PLAN.md
```

- 定向测试：30 项通过，失败/错误/跳过均为 0。
- 完整门禁：`BUILD SUCCESSFUL`；32 个测试类、149 项测试，失败/错误/跳过均为 0。
- `jarGate`：`mode=EMBEDDED`，`unrelocatedKotlin=0`，`relocatedKotlin=1029`，
  `reflectImpl=0`，`cubexModuleEntries=121`，`ownClasses=264`，
  `pluginBytecodeMajors=[61]`，`sharedBytecodeMajor=61`。
- 产物：`Contract/build/libs/contract-0.1.0.jar`。
- 金额测试覆盖 3–20 人、每名成员分别违约、非 UUID 顺序的名单、0.01 本金/不能整除的尾差，逐个来源核对金额守恒。
- 存档测试覆盖部分签署、全员审批、重载分配一致、混合旧 SERVICE、缺失/外来/非整数时间签署。
- 迁移测试覆盖中英文 v4→v5、保留已修改文案、重复执行不重复迁移；所有状态都有两种语言标签。
- 初次普通权限执行因 Gradle 网络权限受限失败，经权限请求后运行。首次编译测试遇到新 Java
  测试中的泛型推断错误，修正后重跑成功；没有跳过失败测试。
- 已知警告：Gradle 9 不兼容的弃用功能提示、vendored Metrics/API 弃用提示、JVM CDS 提示；
  没有据此升级工具链。diff 仅有 Git 的 LF/CRLF 提示，无空白错误。

## 真人验证、迁移与回滚

- 本轮未启动 Paper/Folia/Vault，遵照用户暂缓真人测试的要求；单测不代表真实资金/重启链路已验。
- `alliance.version: 1` 包含 `signatures: [{uuid, accepted-at}]` 和 `approvals: [uuid]`；
  参与者的 MONEY stake 仍是唯一的本金条款。
- 有 ALLIANCE 记录时不得降级：旧版不认识 `PENDING_ACCEPT_MULTI` / `ALL_APPROVE`，可能跳过记录。
  修改或恢复这类数据前需备份；不要以删除签署块或自动采用旧备份绕过加载错误。
- lang v5 只补缺失标签，旧文案保留；迁移框架按现有机制备份文件。

## 后续交接

根 `PLAN.md` §5.1 的“下一可执行切片：资金 service”仍未完成，优先处理逐成员 Vault 注资与
pending journal 恢复，再开放命令/GUI。特别是当前通用 `-accept` 恢复仅看合同总状态，不能直接
复用于部分签署的 ALLIANCE；动态结算也必须先持久化执行意图，不能把本计算结果直接循环付款。
本记录仅为已完成切片的证据，不构成第二份计划文件。
