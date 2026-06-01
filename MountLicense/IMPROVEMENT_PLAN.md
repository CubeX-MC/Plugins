# MountLicense Improvement Plan

生成日期：2026-05-21  
最近更新：2026-05-22  
来源：代码、配置、README、项目代理文档审查，以及 `mvn verify` 结果。

## 当前结论

MountLicense 已实现注册、PDC 标识、YAML 索引、保护、停车/锁定、钥匙召回、定位和 Phase 5a trust。2026-05-22 已完成本计划中的代码、文档和自动测试项：权限实现与 README/plugin.yml 一致，召回安全判断覆盖 feet/head，默认 profile 对 1.18.2 API 的新实体名可安全跳过，项目不再是零测试状态，Vault 注册失败路径已有退款/回滚保护。

仍不能直接标成稳定版的原因：真实 Spigot/Paper 服务器回归尚未执行，Bukkit 事件、实体 AI、PDC、Inventory 和 Vault provider 行为需要实服证据。

验证基线：

| 检查项 | 结果 |
| --- | --- |
| `mvn test` | 通过，12 tests |
| `mvn verify` | 通过，12 tests，shaded jar 已生成 |
| 自动测试 | 已覆盖 profile 解析、VehicleIndex 保存读取、OwnershipService、RecallService 安全位置和基础命令权限 |
| 产物 | `target/mountlicense-0.1.0.jar` |
| 运行依赖 | Vault 可选；注册收费依赖 Vault economy provider |

## 优先级定义

| 优先级 | 含义 |
| --- | --- |
| P0 | 阻塞发布或可能导致载具/资金错误 |
| P1 | 影响主要功能可信度，需要在公开 beta 前完成 |
| P2 | 提升可维护性、可测试性和运营体验 |
| P3 | 后续增强，不阻塞内测 |

## 待完善事项

| 优先级 | 状态 | 工作项 | 影响 | 涉及位置 | 验收标准 |
| --- | --- | --- | --- | --- | --- |
| P1 | 已完成 | 统一基础命令权限检查 | README/plugin.yml 声明 `/ml help/list/info/locate` 需要 `mountlicense.use`，源码已统一检查 | `command/MountLicenseCommand.java`, `plugin.yml`, `README.md` | 无 `mountlicense.use` 的玩家无法执行基础查询；admin 子命令仍按 admin 权限独立检查 |
| P1 | 已完成 | 修正召回安全位置检查 | 召回目的地现在要求 feet/head 可通过、非液体、非危险方块，脚下为安全实心方块 | `service/RecallService.java`, `config.yml` | unsafe 目的地会清除本次冷却；新增单测覆盖阻塞头部、阻塞脚下、液体和危险方块 |
| P1 | 已完成 | 明确 1.18.2 与默认实体类型兼容性 | 默认 profile 可包含新实体名；旧 API 会跳过未知类型，不再因空 entityTypes 崩溃 | `vehicle-profiles.yml`, `ProfileRegistry.java`, `VehicleProfile.java`, `README.md` | README 明确 `CAMEL` 1.20+、`CHEST_BOAT` 1.19+；单测覆盖未知实体跳过 |
| P1 | 已完成 | 增加自动测试骨架 | 已建立 JUnit 5/Mockito 单测 | `src/test/java` | 覆盖 profile 解析、VehicleIndex 保存读取、OwnershipService、RecallService 安全位置和基础命令权限 |
| P2 | 已完成 | 清理 README 已过期限制 | README 不再写“没有信任列表”或“没有自动测试” | `README.md` | 只保留 0.1.x 在线 trust 决策和实服回归缺口 |
| P2 | 已完成 | 检查 Vault 收费失败与回滚语义 | 注册收费后若写入/消耗流程抛异常，会移除 PDC/index/tag、尝试退款、清除冷却并记录日志 | `RegistryService.tryRegister`, `EconomyHook`, lang files | 自动退款失败会写 warning；实服 Vault provider 行为仍需手动验证 |
| P2 | 已完成 | 完成 trust 离线玩家支持决策 | 决策为 0.1.x 仅支持在线精确玩家名，离线 UUID 解析推迟 | README | 避免改名/缓存/歧义问题；未来如需要持久玩家索引再设计 |
| P2 | 需实服执行 | 补真实服务器回归记录 | 已补可执行记录表，但不能在本地无服务器环境伪造结果 | README, `docs/manual-regression.md` | 发布稳定版前需填入注册、保护、自动停车、召回、locate、trust、reload/restart、Vault 的手动结果 |
| P3 | 已完成 | 收敛 station/rental 路线图 | PLAN 现在明确 station 退出 core MVP；公共 station/rental 归入可选 Phase 5b | `PLAN.md`, `README.md`, model station 字段说明 | 避免 Phase 4 与 README 冲突 |
| P3 | 已完成 | 可选集成边界文档 | README/PLAN 明确 Phase 6 集成不是当前承诺 | `PLAN.md`, README | Vault 公共账本、Dynmap、Lands 仍为未实现可选集成 |

## 推荐实施顺序

1. 已完成权限检查和召回安全判断。
2. 已同步 README 的 trust/版本兼容说明。
3. 已建立测试骨架并通过 `mvn test` / `mvn verify`。
4. 已为 Vault 注册失败路径增加退款/回滚保护。
5. 下一步只剩真实服务器手动验证，并把结果填入 `docs/manual-regression.md`。

## 发布前检查清单

- [x] `mvn verify` 通过。
- [x] 不再是零测试项目。
- [x] `mountlicense.use` 与 README/plugin.yml/命令实现一致。
- [x] 召回不会把载具传送到头顶阻塞或危险位置。
- [x] README 对 1.18.2、Camel、Chest Boat、Vault 可选收费说明准确。
- [ ] 已记录真实服务器验证结果和仍未覆盖的事件路径。
