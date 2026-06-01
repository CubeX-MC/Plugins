# BookLite Improvement Plan

生成日期：2026-05-21  
复查日期：2026-05-24  
来源：代码、配置、README、项目代理文档审查，以及 Maven 验证结果。

## 当前结论

BookLite 已经具备核心闭环：SQLite 存储、PDC 空壳书、签书转换、右键阅读、工作台复制、软删除、恢复、卸载模式和基础讲台读取兼容。当前主要短板不是功能数量，而是讲台交互边界、自动化测试覆盖、本地化一致性和发布级运行证据。

验证基线：

| 检查项 | 结果 |
| --- | --- |
| `mvn verify` | 通过（2026-05-24 复跑） |
| 自动测试 | 33 个测试通过（model / service / storage） |
| 产物 | `target/booklite-0.1.0.jar` |
| 已知说明 | 仅基于 Bukkit/Spigot `BookMeta` API，不承诺 1.20.5+ data component 细节完整保留 |

## 优先级定义

| 优先级 | 含义 |
| --- | --- |
| P0 | 阻塞发布或可能导致数据/物品错误 |
| P1 | 影响主要功能可信度，需要在公开 beta 前完成 |
| P2 | 提升可维护性、可测试性和运营体验 |
| P3 | 后续增强，不阻塞内测 |

## 待完善事项

| 优先级 | 状态 | 工作项 | 影响 | 涉及位置 | 验收标准 |
| --- | --- | --- | --- | --- | --- |
| P1 | 已完成 | 修正讲台放置与读取交互边界 | 当前右键 BookLite 空壳书会取消 `RIGHT_CLICK_BLOCK` 并打开虚拟书，可能拦截玩家把空壳书放入空讲台；README 已声明讲台可持有空壳书 | `listener/BookListener.java`, `README.md` | 空讲台右键可放入 BookLite 空壳书；已有 BookLite 书的讲台右键仍打开数据库内容；普通方块右键不误触关键原版行为 |
| P1 | 已完成 | 为核心序列化和存储补单元测试 | 复制代数、软删除、恢复和哈希去重缺少回归保护 | `service/BookCodec.java`, `storage/BookRepository.java`, `model/BookRecord.java` | 增加 `src/test/java`；覆盖 hash 去重、generation 递增/上限、软删除/undelete/purge、缺失/删除书降级 |
| P1 | 自动化已完成；真实服务器待执行 | 补一次真实服务器手动回归记录 | 单元测试无法覆盖 Bukkit 事件、讲台、容器和卸载模式 | `docs/manual-regression.md` | 记录签书、右键读、复制、restore、delete/undelete、purge、lectern、uninstall restorecontainer 的手动结果 |
| P2 | 已完成 | 统一玩家侧透明外观，移除硬编码英文 lore | 默认 `language: zh_CN`，但 shell 页面和 lore 曾暴露英文 BookLite 占位信息 | `config.yml`, `BookCodec`, `lang/*.yml` | 空壳书可见标题/作者保持原书信息；普通右键从后端读取完整内容；不再默认展示 BookLite 占位页或 lore |
| P2 | 已完成 | 明确 SQLite 同步写入策略并补压力说明 | 签书和 `/booklite convert` 当前同步调用 `books.saveOrGet`；计划文档建议异步写库/单写队列 | `docs/build-notes.md`, `README.md` | README 或项目文档说明当前同步策略的边界；如改为异步，需要失败回滚/待修复队列 |
| P2 | 已完成 | 补短 ID 歧义提示 | `resolve` 当前前缀匹配不唯一时与找不到共用失败结果 | `BookService.resolve`, `BookLiteCommand` | 管理命令能区分“找不到”和“短 ID 不唯一” |
| P2 | 已完成 | 检查 shade 警告并记录是否可接受 | `mvn verify` 通过但有 sqlite/slf4j/gson overlapping warnings | `pom.xml`, `docs/build-notes.md` | 文档记录警告可接受原因，或调整 shade filters 降低噪音 |
| P3 | 已决策（不进入 0.1.x） | 扩展导出/导入或离线扫描工具 | 计划中提到 export/import/scanloaded，当前未实现 | `docs/build-notes.md` | 明确是否进入下个版本；不做则从发布承诺中移除 |
| P3 | 不阻塞 0.1.x；真实服务器待执行 | 对 1.20.5+ data component 做专项兼容探索 | 当前 README 已降级声明，但未验证现代组件保真 | `docs/build-notes.md` | 在 Paper 新版本上记录 title/page/click event/generation 的保真结果 |

## 实施结果（2026-05-21）

- 讲台边界：`BookListener` 新增 `shouldOpenHeldBook`，对可交互方块（空讲台、
  箱子、门）放行原版行为，仅在右键空气或非交互方块时打开虚拟书。
- 测试骨架：新增 `src/test/java`，`mvn verify` 运行 33 个测试全部通过
  （`BookRecordTest` 6、`BookCodecTest` 13、`BookRepositoryTest` 14）。
- 玩家侧透明外观：空壳书标题/作者保持原书信息，默认不再展示 BookLite
  占位页或 lore；缺失/已删除系统书文本仍保留在 `lang/*.yml`。
- 短 ID 歧义：`BookService.resolve` 返回 `Resolution`，命令层区分
  “找不到”与“短 ID 不唯一”（`commands.ambiguous_id`）。
- shade 警告：补充 filter 后 `mvn clean verify` 不再输出 overlapping 警告；若在
  未清理 `target` 的情况下重复运行 `mvn verify`，shade 可能把上一次的 shaded jar
  当作输入再次比较并输出 overlap 噪音，发布级证据以干净构建为准。
- 文档：新增 `docs/manual-regression.md`、`docs/build-notes.md`。
- 仍需人工执行：真实服务器手动回归（场景 1-18）与 1.20.5+ 兼容探索。

## 复查结果（2026-05-24）

- `mvn verify`：通过，33 个测试通过，构建 `target/booklite-0.1.0.jar`。
- 结论：自动化和文档准备项已完成；真实服务器手动回归仍需在 Paper/Spigot
  服务器与客户端内执行，不能由本地 Maven 验证替代。

## 推荐实施顺序

1. 修复讲台放置/读取边界，并补对应手动回归说明。
2. 建立测试骨架，先测 `BookCodec` 和 `BookRepository`。
3. 统一本地化输出，把硬编码 lore/placeholder 收口。
4. 跑一次 release 级手动验证，形成可附到 release notes 的证据。
5. 再评估异步写库/队列化是否需要进入 0.1.x。

## 发布前检查清单

- [x] `mvn verify` 通过（2026-05-24）。
- [x] 至少有核心单元测试，不再显示 `No tests to run`（33 个测试通过）。
- [ ] 讲台放置、读取、取下三段流程有真实服务器手动验证（见 `docs/manual-regression.md` 场景 3-5）。
- [ ] 卸载模式在玩家背包和容器中各完成一次真实服务器验证（见 `docs/manual-regression.md` 场景 15-16）。
- [x] README 的兼容声明、构建产物名、命令和权限与源码一致。
- [x] 已记录无法自动覆盖的真实服务器风险（`docs/manual-regression.md`）。
