# BookLite

![](https://bstats.org/signatures/bukkit/BookLite.svg)

BookLite 是一个面向 Paper / Spigot 服务器的轻量化成书存储插件。

它不会把每一本成书的完整页面都塞在物品数据里，而是把正文保存到本地 SQLite 数据库中，物品本身只保留极小的 BookLite 标记。玩家仍然可以通过接近原版的方式写书、读书、复制书，管理员也能审查、软删除、恢复和最终清理书籍数据。

## 定位

BookLite 的目标不是限制书本内容，而是把 Written Book 的大体积正文从物品数据里**剥离**出去，
让实体物品只保留一个轻量标识。

核心价值：

- 允许玩家大量保存、复制、陈列书籍，降低箱子、潜影盒、讲台、图书馆区域的物品数据负担。
- 保留接近原版的写书、读书、复制、讲台体验。
- 降低大书、恶意书、书本数据溢出对区块加载、玩家登录、容器同步造成的风险。
- 提供可审查、可删除、可恢复、**可退出**的图书内容治理能力，避免插件数据绑架。

与同类方案的差异：PacketBooks 等插件也会把书内容外置，但定位更偏向修数据溢出与书本漏洞；
BookExploitFix 偏向过滤危险点击事件；Paper 原生配置提供的是页数与体积上限。
BookLite 更适合被当作**服务器图书馆基础设施**：可查询的 SQLite 数据库、内容去重、
管理员可审查追踪、以及平滑的迁出路径。

**不做什么**（避免误装）：

- 不是"防禁人书插件"。它不过滤书页里的危险点击事件，那是 BookExploitFix 一类插件的职责。
- 不设书本体积/页数硬上限来对抗漏洞——那用 Paper 原生配置更合适（本插件另有自己的 `limits` 用于存储保护）。

## 功能特性

- 使用 SQLite 保存成书内容。
- 基于内容哈希去重：相同标题、作者和页面内容只保存一份正文。
- 物品可见标题与作者保持原书信息，仅隐藏保存 `book_id`、`generation` 和 schema version 等 PDC 标记。
- `/booklite convert` 可将已有原版成书转换为空壳书。
- `/booklite restore` 可将 BookLite 空壳书还原为原版成书。
- 玩家签名成书时可自动接管并轻量化。
- 右键 BookLite 空壳书时从数据库读取并打开完整内容。
- 支持工作台复制，正确递增复制代数并限制 copy-of-copy。
- 管理员可 list/read/info/get/delete/undelete/status。
- 支持软删除，已删除书籍打开时只显示友好提示，不泄露原文。
- 支持 `/booklite purge <天数> confirm` 永久清理已软删除记录。
- 记录每本书最近一次成功读取时间，便于管理员判断长期未访问记录。
- 基础讲台读取兼容：讲台上放置 BookLite 空壳书后，右键可打开数据库中的内容。
- 卸载模式支持被动恢复玩家背包和打开过的容器，并提供 `/booklite restorecontainer` 恢复视线目标容器。
- 管理命令支持 `/booklite list` 显示的短 ID，`info/read/get/delete/undelete` 可 Tab 补全完整 ID。

## 运行要求

| 项 | 要求 |
|---|---|
| 服务端 | Spigot / Paper 1.18+ |
| Java | 17 |
| 必需依赖 | 无（SQLite 已内置） |
| Folia | 支持 |

## 安装

1. 把 `booklite-<version>.jar` 放进服务器 `plugins/`。
2. 启动服务器生成默认配置与 `books.db`。
3. 按需修改 `config.yml` 后 `/booklite reload`。

> 部署用的是 `build/libs/booklite-<version>.jar`；同目录的 `*-plain.jar` **不要**部署。

## 命令

别名：`/bl`

| 命令 | 权限 | 说明 |
| --- | --- | --- |
| `/booklite help` | 任意玩家 | 查看可用命令 |
| `/booklite convert` | `booklite.convert` | 将手中原版成书转换为 BookLite 空壳书 |
| `/booklite restore` | `booklite.restore` | 将手中 BookLite 空壳书还原为原版成书 |
| `/booklite status` | `booklite.admin.status` | 查看数据库、缓存和卸载模式状态 |
| `/booklite list [页码]` | `booklite.admin.list` | 分页列出数据库中的书籍 |
| `/booklite info <id>` | `booklite.admin.info` | 查看单本书元数据，支持短 ID |
| `/booklite read <id>` | `booklite.admin.read` | 管理员打开数据库中的书 |
| `/booklite get <id>` | `booklite.admin.get` | 获取绑定指定 ID 的 BookLite 空壳书 |
| `/booklite delete <id>` | `booklite.admin.delete` | 软删除一本书 |
| `/booklite undelete <id>` | `booklite.admin.delete` | 撤销软删除 |
| `/booklite purge <天数> confirm` | `booklite.admin.purge` | 永久清理软删除时间超过指定天数的书。填 `0` 表示清理全部已软删除记录 |
| `/booklite restorecontainer` | `booklite.admin.restorecontainer` | 恢复视线 6 格内目标容器中的 BookLite 空壳书 |
| `/booklite reload` | `booklite.admin.reload` | 补齐缺失的默认配置/语言文件，并重新加载配置与语言 |

## 权限

- `booklite.use`：允许阅读 BookLite 书籍。
- `booklite.convert`：允许转换手中原版成书。
- `booklite.restore`：允许还原手中 BookLite 空壳书。
- `booklite.admin`：管理员父权限，包含 status、reload、list、info、read、get、delete、purge、restorecontainer。

## 配置示例

```yaml
language: zh_CN

storage:
  sqlite_file: "books.db"
  wal: true

cache:
  maximum_size: 2048
  expire_after_access_minutes: 15

limits:
  max_pages: 100
  max_page_json_bytes: 8192
  max_total_json_bytes: 262144

behavior:
  auto_convert_signed_books: true
  allow_crafting_copy: true
  preserve_generation: true
  # BookLite 空壳书保持接近原版的可见外观：标题和作者仍是原书信息，
  # 只有隐藏 PDC 标记指向数据库内容。

lectern:
  enabled: true

uninstall:
  mode: false
  passive_on_player_join: true
  passive_on_inventory_open: true
  max_items_per_tick: 64

logging:
  log_conversions: true
  log_restores: true
  log_admin_reads: true
```

旧配置中若仍有 `behavior.shell_title_format` 或 `behavior.shell_author`，当前版本会忽略它们；
BookLite 书默认保持原书标题和作者，避免玩家看到存储实现细节。

## 数据模型

BookLite 会在插件数据目录中创建 `books.db`，并维护 `books` 表：

- `id`：书籍 UUID。
- `content_hash`：标题、作者和页面内容计算出的哈希，用于去重。
- `title_raw`：原始标题。
- `author`：作者。
- `pages_json`：页面内容 JSON。
- `total_pages`：页数。
- `created_at` / `updated_at`：创建和更新时间。
- `last_accessed_at`：最近一次成功读取完整内容的时间，未读取时为空。
- `deleted_at`：软删除时间，未删除时为空。

空壳物品只保存：

- `booklite:book_id`
- `booklite:generation`
- `booklite:version`

启用 WAL 时，SQLite 会使用 `journal_mode = WAL` 和 `synchronous = NORMAL`，并设置 5 秒 busy timeout。

BookLite 不会自动根据“当前存档里是否还能扫描到物品”来物理删除记录。离线玩家、
未加载区块、潜影盒、外部菜单或备份恢复都可能让自动引用计数误判。推荐通过
`/booklite list` 和 `/booklite status` 观察最近访问时间，先软删除，再用
`/booklite purge <天数> confirm` 做确认式清理。

## 写入策略

签书转换和 `/booklite convert` 会在主线程同步写入 SQLite，阅读和卸载恢复路径
为异步。同步写入的边界、权衡与后续是否队列化的评估见
[docs/build-notes.md](docs/build-notes.md)。

## 卸载模式

如果准备移除插件，先把配置中的：

```yaml
uninstall:
  mode: true
```

改为 `true` 并重载插件。开启后：

- 新签名的书不再自动转换为 BookLite 空壳书。
- 玩家上线时可被动恢复背包中的 BookLite 书。
- 玩家打开容器时可被动恢复容器内的 BookLite 书。
- 管理员可看向 6 格内的容器方块并执行 `/booklite restorecontainer`，主动恢复该容器。

确认常用背包和容器都恢复完成后，再移除插件会更稳。

## 兼容说明

BookLite 当前基于 Bukkit / Spigot 的 `BookMeta` API，兼容面较广，但不承诺保留 1.20.5+ 所有底层 data component 细节。

讲台支持目前定位为“读取兼容”：讲台可以持有 BookLite 空壳书，玩家右键讲台时会打开数据库中的完整内容。比较器输出、原版讲台翻页状态和复杂红石装置行为暂不宣称完全一致。

## 构建

```powershell
.\gradlew.bat :BookLite:build      # 编译 + 测试 + 部署 jar
.\gradlew.bat :BookLite:test       # 只跑测试
.\gradlew.bat :BookLite:jarGate    # 部署 jar 门禁
```

Windows 必须用 PowerShell 跑 `.\gradlew.bat`（仓库路径含空格）。
产物在 `BookLite/build/libs/booklite-<version>.jar`。

## 已知边界

- 基于 Bukkit / Spigot `BookMeta` API，**不承诺保留 1.20.5+ 所有底层 data component 细节**。
- 讲台是"读取兼容"：可以放 BookLite 空壳书并右键打开完整内容，但比较器输出、
  原版讲台翻页状态与复杂红石装置行为不宣称完全一致。
- 不会根据"当前存档里是否还扫得到物品"自动物理删除记录——离线玩家、未加载区块、
  潜影盒、外部菜单与备份恢复都会让引用计数误判。清理请走软删除 + `purge` 确认式流程。

## 相关文档

- 待办与路线：仓库根 [`PLAN.md`](../PLAN.md)
- 写入策略与权衡：[docs/build-notes.md](docs/build-notes.md)
