# Regions contributor guide

## Required checks

对 Regions 的代码、资源或构建配置做修改后，至少运行：

```text
./gradlew :Regions:test
./gradlew :Regions:build
```

涉及依赖、打包或发布时，还要运行 `./gradlew :Regions:shadowJar --rerun-tasks`，并确认部署的是非 `plain` JAR。

## Safety invariants

- 玩家临时效果必须通过 `ScopedEffectService` 持有和恢复，不能只在 Mode 内保存内存快照。
- 会替换装备的 Mode 必须先成功持久化 escrow，再修改装备；恢复成功后才能删除 escrow。
- 延迟/实体任务必须校验当前 state 实例、active 状态和玩家成员关系。
- Paper 与 Folia 的实体访问必须走 `CubexScheduler`；停服时不能依赖新调度任务执行。
- 发布验证必须递归检查 Action 中嵌套的 Effect/Action 参数。
- 不得绕过 draft → validate/preview → publish 的 revision 流程直接改变运行态定义。

新增能力时同步更新 `BuiltInRegionCapabilities`、验证器、GUI/模板、语言资源、回归测试和相关文档。

