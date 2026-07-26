# Changelog

## Unreleased

### Added

- `on_interact` 与 `on_timer` 的运行时触发点；`on_timer` 有可配置间隔与 5 秒下限。
- TRIGGER 纳入 Capability Catalog，启动时校验枚举与 descriptor 一致。
- `RegionSource.containing` 批量位置解析，Lands 每次检测只做一次反射解析。
- GUI、命令与 Mode 运行时文案的完整 zh_CN / en_US 语言键（`lang-version: 5`）。
- 语言文件回归测试：键集一致、基线版本一致、en_US 无源语言残留。

### Changed

- `RegionsGui` 拆分为协调器加五个菜单类及共享的文案/取值/物品辅助，单文件从 1436 行降到 257 行以内。
- Effect escrow 从每次应用/恢复整文件重写改为按批写入；进出区域的一组 Effect 只写一次。
- `RegionDetectionService` 按来源分组、缓存已发布集合，并按 `RegionStorage.publishedRevision()` 失效。

- 持久化 Effect lease 托管与启动/登录恢复。
- `glowing` 效果能力及捉迷藏角色视觉的租约化管理。
- 赛跑 `timeout-seconds` 自动结束和 GUI/模板配置。
- 模式旧任务隔离、结束恢复闸门及对应自动化回归。
- 深层 Action/Effect、物品、药水、声音、位置和赛跑超时验证。
- Regions 独立 CI 构建、产物上传及标签发布支持。

### Fixed

- `fly: deny` 现在对飞行中进入区域的玩家同样生效：合成 `allow_flight` Effect 托管并在离开时恢复，不再只拦截区域内的起飞按键。
- 移除永不触发的 `on_score`；未知 trigger 键在加载时明确警告，而不是静默丢弃。
- Paper 停服清理不再排入必然被取消的下一 tick。
- Folia 停服保留未安全恢复的效果/装备托管，供下次启动恢复。
- 玩家离开后排队的战斗/回合启动任务不再修改玩家状态。
- 旧回合/比赛计时器不再影响同 id 的新一局。
- Effect 应用失败后不再缓存成功签名，后续刷新可自动重试。
- Lands 已安装但禁用时不再错误报告 Source 可用。
- 装备托管写入或删除失败时回滚内存状态，避免内存与磁盘分叉。

