# 自动回归基线

合并或部署候选版本必须满足：

```text
./gradlew :Regions:test
./gradlew :Regions:build
./gradlew :Regions:shadowJar --rerun-tasks
```

当前自动化覆盖以下风险域：

- 配置文本、默认区域与模板结构。
- capability 真值、深层 Action/Effect 和运行时 registry 参数验证。
- 权限、所有权、发布、revision diff、rollback、模板和审计存储。
- 重叠解析、Effect 组合失败重试、持久化 lease 跨实例恢复。
- Lands 禁用状态、trial 隔离和 Paper 停服同步会话清理。
- 赛跑超时、旧比赛/回合计时器隔离、玩家离开后的旧战斗启动任务隔离。
- Region 存储 schema/revision 读写，以及坏 revision/lease 的 fail-closed reload。
- `deny` Flag 到 Effect 的合成桥接、显式 Effect 的优先级、以及 bypass/创造模式豁免。
- Trigger 枚举与 TRIGGER descriptor 的能力真值一致性。
- Effect escrow 批量写入的原子性与恢复后清空。
- 检测按来源分组、已发布集合缓存失效与不可用来源短路。
- zh_CN/en_US 键集一致性、基线版本一致性与 en_US 无源语言残留。
- Contract API 反射 ABI、WAGER 参与方/工会胜者映射、operation 冲突、provider 暂停后的 SETTLING 重放与终态清理。

自动化不能替代真实服务端线程模型、外部插件行为、GUI 交互和多人体验；这些由 `REAL_PLAYER_TEST.md` 覆盖。任何失败测试、测试报告中的 skipped、启动栈追踪、线程违规或无法恢复的 escrow 都是测试环境准入阻断项。

