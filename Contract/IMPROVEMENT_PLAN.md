# Contract Improvement Plan

生成日期：2026-05-21  
来源：代码、配置、README、PLAN、DESIGN 审查，以及 `mvn verify` 结果。

## 当前结论

Contract 是三个项目里自动化测试基础最好的一个：模型层抽象、WAGER、PARTNERSHIP、可选中间人、pending 事务存储、基础 SERVICE 流程、签名 token 与创建向导草稿校验已有 53 个单元测试通过。资金核心的 P0/P1/P2 收口项已完成；“全 GUI 合同工作流”和“铁砧签署仪式感”（GUI-1~GUI-6）已全部落地，玩家无需记命令即可完成创建、接受、确认、裁决和管理。

验证基线：

| 检查项 | 结果 |
| --- | --- |
| `mvn verify` | 通过 |
| 自动测试 | 53 tests, 0 failures, 0 errors |
| 产物 | `target/contract-0.1.0.jar`（AnvilGUI 已 shade 并重定位打包） |
| 必需依赖 | Vault 和 Vault-compatible economy provider |

## 优先级定义

| 优先级 | 含义 |
| --- | --- |
| P0 | 阻塞发布或可能导致资金/数据错误 |
| P1 | 影响主要功能可信度，需要在公开 beta 前完成 |
| P2 | 提升可维护性、可测试性和运营体验 |
| P3 | 后续增强，不阻塞内测 |

## 待完善事项

| 优先级 | 状态 | 工作项 | 影响 | 涉及位置 | 验收标准 |
| --- | --- | --- | --- | --- | --- |
| P0 | 完成 | 强化结算 idempotency 和 pending deposit 记录 | 结算先写 `SETTLEMENT` pending；每笔 Vault payout deposit 先写 `DEPOSIT` pending。重启恢复不会自动重放 deposit，而是把未完成结算转入 `DISPUTED` 并写事件日志，避免重复付款 | `service/ContractService.java`, `storage/PendingTransactionStore.java`, `storage/EventLog.java` | 每笔 payout 有可恢复事务记录；重启恢复不会重复付款；失败明确停在可人工处理状态 |
| P1 | 完成 | 为结算失败/恢复补测试 | 新增 pending store 持久化测试，覆盖 `WITHDRAW` / `DEPOSIT` / `SETTLEMENT` 读写；新增 generic participant alias 和 optional mediator 测试，避免新类型在恢复/列表/权限视角漏判 | `src/test/java/org/cubexmc/contract/storage/PendingTransactionStoreTest.java`, `src/test/java/org/cubexmc/contract/model/ContractTest.java` | `mvn verify` 43 tests 通过；真实 Vault 中途失败仍需 live/manual smoke |
| P1 | 完成 | 同步 README 到当前命令实现 | README 已补 `/contract wager`、`partner`、`resolve`、新状态、资金规则、pending 恢复和 admin close 语义 | `README.md`, `ContractCommand.java`, `lang/zh_CN.yml`, `lang/en_US.yml` | README 命令、资金规则、状态说明与源码一致 |
| P1 | 完成 | 完成 `info` 的类型化详情 | `info` 展示类型、participants、押注、arbiter、payout 条件预览和 partnership 双方确认状态 | `command/ContractCommand.java:sendInfo`, `model/*` | SERVICE/WAGER/PARTNERSHIP 分别显示关键参与者、押注、仲裁/双方确认状态和结算预览 |
| P1 | 完成 | GUI 支持 PENDING_ACCEPT 和新类型 | “我的合同”改用 generic related participant 视角；待接受邀请可直接 accept；WAGER 仲裁者有 A/B 裁决入口；PARTNERSHIP 双方有确认入口 | `gui/ContractGui.java`, `model/Contract.java` | “我的合同”能看到待接受邀请；WAGER/PARTNERSHIP 有接受、确认、裁决入口 |
| P2 | 完成 | GUI 类型筛选和图标区分 | GUI 顶部添加全部/SERVICE/WAGER/PARTNERSHIP 筛选；`materialFor(type, status)` 按类型和终态选图标 | `gui/ContractGui.java` | 添加全部/SERVICE/WAGER/PARTNERSHIP 过滤；图标体现 type + status |
| P2 | 完成 | 多语言配置收口 | `config.yml` 增加 `language: zh_CN`；`LanguageManager` 按配置加载；新增 `lang/en_US.yml`；类型/角色/条件标签本地化 | `config/LanguageManager.java`, `src/main/resources/lang`, `config.yml` | 支持 `language: zh_CN` / `en_US` 配置 |
| P2 | 完成 | reload 后关闭或刷新旧 GUI session | `reloadContracts` 会调用 `ContractGui.closeSessions()` 清理旧 inventory session 和争议输入 prompt | `ContractPlugin.reloadContracts`, `ContractGui` | `/contract admin reload` 后旧 GUI 关闭，避免旧数据操作 |
| P2 | 完成 | Admin close 资金语义再确认 | README 和语言提示明确 close 不移动资金；资金处理应使用 admin pay/refund 或人工核对后 close | `ContractService.adminClose`, README, locale files | README 明确 close 是“无资金移动/人工处理” |
| P2 | 完成 | SERVICE / PARTNERSHIP 可选中间人 | `/contract service` 和 `/contract partner` 支持 `--mediator <玩家>`；中间人需先接受职责，再裁决 pay/refund/owner/contractor；资金仍由服务器托管和 settlement pending 处理 | `ContractCommand.java`, `ContractService.java`, `ContractGui.java`, README, locale files | 子命令按类型命名；中间人不是参与方/收款方；裁决不绕过服务层结算 |
| P1 | 完成 | 全 GUI 创建向导 | `/contract` 打开合同工作台；创建向导先选类型，标题/玩家名/金额/押注/中间人/期限用铁砧输入，描述用聊天文本输入，实时显示描述预览和扣款明细，签署后调用 `ContractService` 创建 | `gui/ContractGui.java`, `gui/CreateDraft.java`, `gui/ContractTerms.java`, `command/ContractCommand.java` | `/contract` 后可选择类型、填写字段、预览条款、签名并创建 SERVICE/WAGER/PARTNERSHIP；无需输入创建命令 |
| P1 | 完成 | 铁砧签名确认层 | 创建、接受邀请、接单、确认付款、裁决、取消、管理员 pay/refund/close 都先进确认页展示资金后果，再打开铁砧要求输入玩家名或“同意”；签名不符或关闭铁砧即取消 | `gui/ContractGui.java`, `gui/Signature.java`, AnvilGUI 依赖 | 关键资金动作前需通过铁砧签名确认；取消/返回不会产生资金动作 |
| P2 | 完成 | 我的工作台与行动收件箱 | 工作台首页显示行动收件箱待办数量；行动收件箱集中显示需要当前玩家接受/提交/确认/裁决/处理争议的合同 | `gui/ContractGui.java` | 首页显示行动数量；一键进入“待我处理”；按状态区分待办与历史 |
| P2 | 完成 | 管理员 GUI 工作台 | 管理员工作台按争议/进行中/全部分栏检索，详情页提供强制付款/退款/关闭入口，执行前显示资金后果并签署确认 | `gui/ContractGui.java`, permission checks | 管理员可在 GUI 查看争议/中断结算/全部合同，执行 pay/refund/close 前显示资金后果并签名确认 |
| P3 | 后续 | ALLIANCE / ITEM / SALE / LAND_PERMISSION 路线图拆分 | PLAN 已列多项后续类型，模型有枚举/Asset 占位但未落地 | `PLAN.md`, `model/Asset.java`, `model/ContractType.java` | 后续版本独立拆分；当前 README 不承诺未实现类型 |
| P3 | 后续 | PlaceholderAPI 和声望系统 | PLAN 阶段 D 未完成 | `PLAN.md`, future integration package | 明确后续版本，不阻塞当前合同资金核心 |

## GUI 体验蓝图

参考成熟 Bukkit/Spigot 插件的共同模式，Contract 的 GUI 不应只是命令快捷键，而应成为默认操作系统：`/contract` 打开主界面，所有常用任务都能从清晰分类、可返回、可预览、可确认的库存界面完成。命令保留为高级/脚本入口，但玩家日常不需要记参数顺序。

### 设计原则

1. **一个入口**：`/contract` 永远进入合同工作台，不让玩家先背子命令。
2. **角色优先**：界面按玩家当前身份展示动作：发布者、接单者、被邀请者、中间人、管理员。
3. **行动优先**：首页先显示“需要我处理”的合同，再显示大厅和历史。
4. **资金预览先于动作**：任何扣款、付款、退款、佣金回收都必须在确认页展示金额、对象和后果。
5. **破坏性动作二次确认**：付款、退款、裁决、管理员 close 不能单击触发。
6. **短输入用铁砧，描述用聊天**：金额、小时、玩家名、标题、签名用铁砧界面；描述用聊天文本输入，支持取消和清空，保存后回到创建向导。
7. **所有失败都可解释**：余额不足、权限不足、状态不符、中间人未接受、ID 失效，都在 GUI 内反馈，不让玩家猜。
8. **管理员模式隔离**：普通玩家工作台和管理员工作台视觉上分开，避免误操作。

### 主界面结构

建议 54 格库存作为根界面：

| 区域 | 槽位 | 内容 |
| --- | --- | --- |
| 顶部导航 | 0-8 | 合同大厅、我的工作台、创建合同、中间人工作台、帮助、管理员入口 |
| 中央列表 | 10-43 | 合同卡片；按当前栏目展示 |
| 底部操作 | 45-53 | 上一页、筛选、排序、刷新、下一页、返回 |

首页默认栏目为“我的工作台”，卡片排序：

1. 待我签署或接受的邀请。
2. 待我确认付款 / 合作确认。
3. 我作为中间人待接受或待裁决。
4. 我参与的争议。
5. 我创建或接取的进行中合同。

### 合同大厅

合同大厅用于发现公开 SERVICE 合同和后续可公开领取的类型。顶部筛选应包含：

- 类型：全部、SERVICE、WAGER、PARTNERSHIP，后续扩展 BOUNTY/SALE。
- 状态：公开、待接受、进行中、争议、已结束。
- 金额：低/中/高，或按配置档位。
- 排序：最新、即将到期、金额最高。

每张合同卡片至少显示：

- 合同 ID 和类型图标。
- 标题。
- 发起人/对方/中间人。
- 托管金额或押注。
- 状态和剩余时间。
- 是否需要签名、是否存在争议。

### 创建合同向导

创建向导按“先选择、后填写、再预览、最后签名”设计。

1. **选择类型**：SERVICE / WAGER / PARTNERSHIP。未实现类型显示灰色图标和“后续版本”，不允许点击。
2. **填写参与者**：对方玩家、中间人。玩家名用铁砧输入，输入后显示头像/名称校验结果；中间人可跳过。
3. **填写资金**：奖金、押注、双方押注。金额用铁砧输入；GUI 即时显示最低/最高限制、创建费、佣金预估、总扣款。
4. **填写期限**：预设按钮 1h / 6h / 24h / 72h / 自定义；自定义用铁砧输入。
5. **填写条款**：标题用铁砧输入；描述用聊天输入，适合快速填写一句或一段验收说明。
6. **预览条款**：显示合同类型、参与人、托管资金、结算规则、期限、中间人、费用。
7. **铁砧签署**：打开铁砧界面，要求输入自己的玩家名或配置词如 `同意`。签署成功后才调用 service 创建合同。

签署页文案必须明确：

- “签署后将立即扣除 X，托管到服务器。”
- “创建费 Y 不会在普通取消时退回。”
- “资金不会交给中间人或对方。”

### 铁砧签名确认

铁砧签名是 Contract 的仪式感核心，但也要短、明确、可取消。

适用动作：

- 创建合同。
- 接受 SERVICE 接单。
- 接受 WAGER/PARTNERSHIP 邀请并扣押注。
- SERVICE approve 确认付款。
- PARTNERSHIP 双方确认。
- 中间人裁决 pay/refund/owner/contractor。
- 管理员 pay/refund/close。

不适用动作：

- 翻页、筛选、查看详情。
- 提交完成。
- 发起争议文本输入。

推荐机制：

- GUI 点击关键按钮后进入确认页。
- 确认页显示资金后果和参与方。
- 点击“签署”打开铁砧。
- 玩家输入指定短语：默认自己的玩家名，或 `同意`。
- 签名失败不执行动作；关闭界面视为取消。
- 成功后写入事件日志：签署者、动作、合同 ID、时间。

### 合同详情页

详情页应分为三层信息：

1. **摘要层**：标题、状态、类型、剩余时间、托管总额。
2. **参与者层**：发起人、接单者/对方、中间人及其接受状态。
3. **资金层**：成功、失败、超时、裁决各自的资金流向预览。

动作按钮只显示当前玩家可执行的动作。不可执行动作不应只是隐藏；在需要教育玩家时可显示灰色图标说明原因，例如“等待对方接受”“中间人尚未接受职责”“余额不足”。

### 管理员工作台

管理员入口只对 `contract.admin.view` 显示。管理员工作台分栏：

- 争议合同。
- pending settlement 恢复/人工核对。
- 全部合同检索。
- 最近事件日志摘要。

管理员动作必须走确认页和签名：

- `pay`：显示收款人、付款金额、系统回收佣金。
- `refund`：显示退款对象和金额。
- `close`：显示“不移动资金，需要人工确认资金已处理”。

### 实施阶段

| 阶段 | 目标 | 验收 | 状态 |
| --- | --- | --- | --- |
| GUI-1 | 重构 GUI session 为可扩展页面栈 | 返回/刷新/分页不丢上下文；reload 清理所有会话 | 完成 |
| GUI-2 | 我的工作台 + 行动收件箱 | 玩家能一眼看到待接受、待确认、待裁决、争议 | 完成 |
| GUI-3 | 创建向导 SERVICE | 不用命令可创建 SERVICE；铁砧签名后扣款创建 | 完成 |
| GUI-4 | 创建向导 WAGER/PARTNERSHIP | 支持对方、中间人、押注、期限、预览和签名 | 完成 |
| GUI-5 | 关键动作签名确认 | 接受、确认付款、裁决、管理员动作都必须签名 | 完成 |
| GUI-6 | 管理员工作台 | 争议/pending/全部合同可视化处理 | 完成 |

### 验证要求

- 单元测试覆盖创建向导草稿对象、金额校验、签名 token 校验、取消不执行动作。
- 服务层测试确认 GUI 和命令共用同一 `ContractService` 路径，不复制资金逻辑。
- 手动 smoke 覆盖：创建 SERVICE、接受 SERVICE、确认付款、创建 WAGER、接受 WAGER、仲裁裁决、创建 PARTNERSHIP、双方确认、中间人裁决、管理员 refund/close。
- Live server 验证必须包含 Vault provider，确认铁砧关闭、输入错误、余额不足、reload 中断不会产生资金动作。

## 推荐实施顺序

1. P0 payout pending/idempotency 已完成。
2. README、info、基础 GUI、多语言和 reload GUI session 已同步。
3. GUI-1~GUI-6 已完成：工作台、行动收件箱、创建向导、铁砧签署确认和管理员工作台均已落地。
4. 剩余 P3：ALLIANCE / ITEM / SALE / LAND_PERMISSION 类型拆分，以及 PlaceholderAPI 和声望系统，留待后续版本。
5. 上线前建议在带 Vault provider 的真实服务器做一轮手动 smoke（见“验证要求”）。

## 发布前检查清单

- [x] `mvn verify` 通过，测试数不少于当前 39 个；当前为 43 个。
- [x] 结算中断、Vault deposit 失败、保存失败、重启恢复有明确人工恢复流程：未完成 settlement/deposit 恢复为 `DISPUTED`，不自动重复付款。
- [x] README 与 `plugin.yml`、`ContractCommand`、`config.yml` 同步。
- [x] GUI 不会隐藏 WAGER/PARTNERSHIP 的待处理动作。
- [x] `/contract admin close` 的资金后果在文档和提示中足够清楚。
- [x] 未实现合同类型不会被 README 当成可用功能宣传。
