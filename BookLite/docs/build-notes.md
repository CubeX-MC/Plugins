# BookLite 构建与兼容说明

## Shade 打包警告

`maven-shade-plugin` 会把 `sqlite-jdbc`、`gson`、`slf4j-nop` 打进最终
jar。早期构建出现少量 overlapping classes / resources 警告，主要来自：

- 各依赖的 `META-INF/maven/**` 元数据。
- 多版本 jar 中的 `META-INF/versions/*/module-info.class`。
- gson 的 `META-INF/proguard/gson.pro`。

这些都是构建元数据，不影响运行时类加载。当前 `pom.xml` 的 shade filter
已排除上述路径，`mvn clean verify` 不再输出 overlapping 警告。

注意：如果不清理 `target` 就重复运行 `mvn verify`，shade 可能把上一次生成的
shaded jar 当作本轮输入之一，重新报告 slf4j/sqlite/error_prone 等 overlap。
这属于增量构建目录状态导致的噪音；发布级证据以 `mvn clean verify` 或
`mvn clean verify package` 为准。后续新增依赖若在干净构建中再次出现警告，
应先确认是否为同类构建元数据，再决定补充 filter 或在此记录为可接受。

## SQLite 写入策略

当前签书转换（`BookListener.onSign`）和 `/booklite convert`
（`BookLiteCommand.handleConvert`）会在主线程同步调用
`BookService.saveOrGet`，进而同步写入 SQLite。

边界与权衡：

- 写入是单条 `INSERT`，启用 WAL 时延迟很低，正常磁盘下对主线程的影响可忽略。
- 内容哈希去重使重复书不会重复写入。
- 阅读路径（`openBookLite`、`BookRestorer.restoreInventoryAsync`）已是异步，
  不占用主线程。
- 风险集中在异常慢的磁盘或大量玩家同时签书时可能产生短暂主线程停顿。

结论：0.1.x 维持同步写入。若实测出现停顿，再评估引入单写线程队列，并为失败
写入补充回滚 / 待修复队列。该评估不阻塞内测。

## 1.20.5+ data component 兼容探索（待执行）

BookLite 基于 Bukkit / Spigot 的 `BookMeta` API，未承诺保留 1.20.5+ 所有底层
data component 细节。需在 Paper 新版本上记录以下保真结果：

| 检查项 | 预期 | 结果 |
| --- | --- | --- |
| 标题 title | 转换/还原后一致 | 待执行 |
| 页面 pages 文本 | 转换/还原后一致 | 待执行 |
| 页面内 click/hover event | 记录是否保留 | 待执行 |
| 复制代数 generation | 转换/还原后一致 | 待执行 |

执行后据结果更新 `README.md` 的兼容说明。

## 导出 / 导入与离线扫描工具

历史计划提到 export / import / scanloaded 工具。当前 `README.md` 未对这些功能
做发布承诺，因此无需从文档中移除。决定：不进入 0.1.x，列为后续版本候选。
