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
- 每个 `RegionTrigger` 枚举项都必须有运行时触发点，并在 `BuiltInRegionCapabilities` 注册 TRIGGER descriptor；启动期校验会拒绝不一致。
- 没有可取消事件的 `deny` Flag 必须在 `RegionOverlapResolver` 合成对应 Effect，不能只靠监听器；合成 Effect 要沿用 Flag 的豁免规则。
- 玩家可见文案一律走语言文件，不写字面量；新增键必须同时补 `zh_CN` 与 `en_US`。
- 语言文件值是 MiniMessage，占位符写 `<name>`；文案里作为字面量的尖括号（帮助文本中的 `<id>` 之类）必须写成 `\<`，否则会和真占位符同名冲突。
- 共享能力优先用 `modules/cubex-*`：文案渲染走 `LanguageManager`（内部是 `I18nService`），日志走 `log()`，有状态的 store 实现 `Reloadable`/`Terminable` 并用 `bind(store)` 注册。缺能力就改 `modules/`，不要在插件里再写一份。

新增能力时同步更新 `BuiltInRegionCapabilities`、验证器、GUI/模板、语言资源、回归测试和相关文档。

