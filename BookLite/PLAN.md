# BookLite 开发计划

## 1. 项目定位

BookLite 是一个面向 Paper / Folia 服务器的轻量化书籍插件。它的目标不是简单限制书本内容，而是将 Written Book 的大体积内容从物品数据中剥离，外置保存到本地数据库，让实体物品只保留一个轻量标识。

核心价值：

- 允许玩家大量保存、复制、陈列书籍，降低箱子、潜影盒、讲台、图书馆区域中的物品数据负担。
- 保留接近原版的写书、读书、复制、讲台体验。
- 降低大书、恶意书、书本数据溢出对区块加载、玩家登录、容器同步造成的风险。
- 提供可审查、可删除、可恢复、可退出的图书内容治理能力，避免插件数据绑架。

BookLite 应避免只被包装成“防禁人书插件”。这个方向已有 Paper 配置、反漏洞插件和 PacketBooks 等竞品。BookLite 更适合定位为“服务器图书馆基础设施”：书籍轻量化、内容去重、数据库管理、平滑迁移。

## 2. 竞品与差异化

### 2.1 已有类似方案

最接近的已知插件是 PacketBooks：

- 会把书内容外置保存到磁盘。
- 会清空物品中的真实书本内容。
- 只在玩家需要阅读或编辑时临时写回。
- 提供类似 UNDO 的卸载恢复模式。
- 当前定位更偏向数据溢出和书本漏洞修复。

其他相关方案包括：

- BookExploitFix：偏向过滤书页中的危险点击事件。
- AnarchyExploitFixes：偏向限制最大书大小、最大页数、最大物品数据体积。
- Paper 原生配置：提供书本页面、标题、作者、书本总大小、选择器解析等限制项。

### 2.2 BookLite 的差异化方向

BookLite 应重点强化以下能力：

- SQLite 可查询数据库，而不是仅作为透明缓存文件。
- 管理员可通过指令审查、删除、恢复、追踪书籍。
- 同内容复制只保存一份正文，减少重复书籍带来的存储浪费。
- 面向大型图书馆、文明服、剧情服、RPG 服、学校服等需要大量书籍的场景。
- 提供清晰的卸载准备模式、恢复统计、离线恢复工具或迁移工具。
- 可选内容安全策略，而不是强制破坏玩家书本格式。

## 3. 当前 Minecraft 技术背景

1.20.5 之后，Written Book 内容主要由 `minecraft:written_book_content` 数据组件承载，而不是只看传统 NBT 标签。该组件包含：

- `pages`
- `title`
- `author`
- `generation`
- `resolved`

因此 BookLite 的技术表述应从“剥离 NBT”升级为“剥离 Written Book 内容组件 / NBT 负载”。实现时必须兼容 Paper 的 Data Component API，以及旧版 Bukkit `BookMeta` 能力差异。

Paper 也已有部分原生防护能力，例如：

- `item-validation.book.author`
- `item-validation.book.title`
- `item-validation.book.page`
- `book-size.page-max`
- `resolve-selectors-in-books`
- `oversized-item-component-sanitizer`

BookLite 不能依赖“原版完全没有保护”作为唯一卖点，必须提供结构性存储和管理价值。

## 4. 目标用户

优先服务：

- 生存服：玩家想建大型图书馆、档案馆、留言馆。
- 文明服 / 国家服：法律、条约、历史文献、报纸、公告大量存储。
- RPG / 剧情服：任务书、设定集、技能书、剧情文本。
- 学校 / 社区服：教材、规则、教程、活动说明。
- 管理严格的服务器：需要审查违规书籍，又不想直接禁用写书功能。

不优先服务：

- 只想简单封禁书本漏洞的服主。
- 需要跨服云同步、Web 图书馆、复杂权限系统的重型平台。
- 强依赖所有原版红石细节完全一致的技术服，除非讲台兼容经过专项验证。

## 5. MVP 范围

第一阶段只做最小可用闭环：

1. 玩家签名成书时，将完整书籍内容写入 SQLite。
2. 物品中移除真实页面内容，只保留 BookLite 标识。
3. 玩家右键阅读时，从缓存或数据库取回内容，构造临时虚拟书并 `openBook()`。
4. 工作台复制时，新副本复用相同内容记录，避免正文重复入库。
5. `/booklite restore` 将手中虚拟书还原为原版实体书。
6. `/booklite convert` 将手中原版成书转换为 BookLite 虚拟书。
7. 基础配置、日志、错误提示、数据库初始化。

MVP 暂缓：

- 完整讲台兼容。
- 全服扫描还原。
- Web UI。
- 内容搜索。
- 多数据库后端。
- 复杂权限模型。

## 6. 数据模型

### 6.1 核心原则

不要使用 SQLite 自增 ID 作为唯一来源。签书事件发生在主线程，同步流程需要立即得到书籍标识。更稳的方案是由插件先生成 UUID / ULID，然后立刻写入 PDC，再异步写库。

推荐拆分“内容记录”和“物品状态”：

- 正文内容是可去重的。
- `generation` 更接近物品副本状态。
- 同一本书的多个副本可以共享内容，但保留各自的代数。

### 6.2 表结构草案

`books`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | TEXT PRIMARY KEY | UUID / ULID |
| `content_hash` | TEXT INDEX | 正文、标题、作者等计算出的 hash，用于去重 |
| `title_raw` | TEXT | 原始标题 |
| `title_filtered` | TEXT NULL | 可选过滤标题 |
| `author` | TEXT | 原版作者名或 UUID 字符串 |
| `pages_json` | TEXT | 原始 JSON 页面数组，必须无损保存 |
| `filtered_pages_json` | TEXT NULL | 如可取得过滤内容则保存 |
| `total_pages` | INTEGER | 页数 |
| `resolved` | INTEGER | 0 / 1 |
| `created_at` | INTEGER | Unix 时间戳 |
| `updated_at` | INTEGER | Unix 时间戳 |
| `deleted_at` | INTEGER NULL | 软删除时间 |

`book_copies` 可选，第二阶段再做：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `copy_id` | TEXT PRIMARY KEY | 物品级 ID |
| `book_id` | TEXT | 指向 `books.id` |
| `generation` | INTEGER | 0-3 |
| `created_at` | INTEGER | Unix 时间戳 |

MVP 可简化为只在 PDC 存：

- `booklite:book_id`
- `booklite:generation`
- `booklite:schema_version`

### 6.3 软删除策略

`/booklite delete <id>` 不应立即物理删除内容，建议先软删除：

- 虚拟书打开时显示“书籍已被管理员删除”。
- 管理员可配置是否允许恢复。
- 物理清理由 `/booklite purge` 或数据库维护任务完成。

## 7. PDC 设计

物品上的 PDC 应尽量小：

- `booklite:book_id`：字符串 UUID / ULID。
- `booklite:generation`：整数，保留原版复制代数。
- `booklite:version`：整数，用于未来迁移。

不建议将标题、作者、页面摘要等大量内容放入 PDC。若需要 tooltip 展示，可只保存短 hash 或懒加载缓存。

## 8. 事件流程

### 8.1 签名成书

监听 `PlayerEditBookEvent`：

1. 判断是否为签名完成。
2. 提取完整书本内容，包括标题、作者、页面 JSON、过滤内容、generation、resolved。
3. 校验页数、单页大小、总大小，遵守配置。
4. 生成 `book_id`。
5. 主线程立即把玩家手中结果书替换为空壳书，并写入 PDC。
6. 异步写入 SQLite。
7. 写库失败时记录错误，并可选择回滚为原版书或标记为待修复书。

注意：

- 不能异步 INSERT 后再等待自增 ID 改物品。
- 不能在异步线程修改玩家背包或 ItemStack 所属实体。

### 8.2 阅读书籍

监听 `PlayerInteractEvent`：

1. 检查手持物是否有 BookLite PDC。
2. 取消默认交互。
3. 从 LRU 缓存读取书籍。
4. 缓存未命中则异步查 SQLite。
5. 回到主线程构造临时完整 Written Book。
6. 调用 `player.openBook()`。

注意：

- 打开前确认玩家仍在线。
- 如手中物品已改变，应仍允许打开，因为阅读行为已被触发，但不能改错背包。
- 数据缺失时显示友好错误书，不要直接报错。

### 8.3 复制书籍

监听 `PrepareItemCraftEvent` 或更可靠的 craft 相关事件：

1. 检测输入中是否存在 BookLite 虚拟书。
2. 验证复制材料符合原版规则。
3. 输出新空壳书，复用相同 `book_id`。
4. 根据原版规则递增 `generation`，最高限制为 2 或 3，取决于目标版本行为。
5. 禁止复制已软删除或数据缺失的书。

关键修正：

- 不要只把同一个 PDC ID 原样复制，否则 `generation` 会混乱。
- 内容 ID 可共享，物品上的 `generation` 应独立。

### 8.4 原版书转换

`/booklite convert`：

1. 检查手持物是否为原版 Written Book。
2. 提取完整内容。
3. 写入数据库或复用已有 hash。
4. 替换为 BookLite 空壳书。
5. 给出转换成功提示。

### 8.5 手动恢复

`/booklite restore`：

1. 检查手持物是否为 BookLite 虚拟书。
2. 根据 `book_id` 读取数据库。
3. 用 PDC 中的 `generation` 构造完整原版书。
4. 清除 BookLite PDC。
5. 替换玩家手持物。

## 9. 讲台兼容计划

讲台兼容不要在 MVP 宣称“完美”。建议拆成两个阶段。

### 9.1 阶段一：可用兼容

- 玩家将 BookLite 虚拟书放上讲台时，插件生成同页数空白书。
- 空白书保留 PDC。
- 玩家右键讲台阅读时，拦截并打开虚拟完整书。
- 取下讲台书时，恢复为空壳书。

### 9.2 阶段二：红石与翻页验证

需要专项测试：

- 比较器输出是否随翻页变化。
- 玩家用原版讲台 GUI 翻页时，服务端是否记录页码。
- 多人同时阅读讲台是否一致。
- 区块卸载、服务器重启后讲台状态是否可恢复。

如果 `openBook()` 无法驱动讲台当前页，应考虑：

- 临时写入真实内容到讲台，再在关闭或卸载时清理。
- 使用包级方案同步 GUI。
- 降级声明为“阅读兼容”，不承诺完整红石兼容。

## 10. 卸载与恢复

### 10.1 手动恢复

保留 `/booklite restore` 作为最可靠的单本书恢复方式。

### 10.2 卸载准备模式

配置：

```yaml
uninstall_mode: false
restore:
  passive_on_player_join: true
  passive_on_inventory_open: true
  max_items_per_tick: 64
  log_restored_books: true
```

启用后：

- 新写的书不再转入 BookLite，保持原版。
- 玩家上线时扫描背包。
- 玩家打开容器时扫描容器。
- 发现 BookLite 虚拟书后，异步读取内容，主线程分批替换为原版书。
- 控制每 tick 最大恢复数量，避免卡顿。

### 10.3 管理员统计

建议加入：

- `/booklite status`：显示数据库书籍数量、缓存命中、待恢复估算、软删除数量。
- `/booklite restorecontainer`：恢复当前打开容器或视线目标容器。
- `/booklite scanloaded`：只扫描已加载区块中的容器，分批执行。

不要默认全服强扫所有 region 文件。离线 region 扫描应作为独立工具或明确高风险命令。

## 11. 缓存策略

推荐使用 Caffeine 或 Guava Cache。

缓存 key：

- `book_id`

缓存 value：

- 反序列化后的 BookRecord。
- 可选预构造 Component 页面。

建议配置：

```yaml
cache:
  maximum_size: 2048
  expire_after_access_minutes: 15
```

缓存注意事项：

- 删除书籍时必须主动 invalidate。
- restore 不需要删除缓存。
- reload 配置时不应清空所有缓存，除非数据库路径改变。

## 12. 数据库与线程模型

SQLite 适合本地轻量插件，但要严格管理线程：

- 所有 `SELECT`、`INSERT`、`UPDATE`、`DELETE` 放入异步任务。
- 所有 Bukkit API 实体、玩家、背包、方块状态修改回到主线程。
- 使用单写队列或连接池，避免 SQLite 写锁冲突。
- 开启 WAL 模式，改善读写并发。

初始化建议：

```sql
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
PRAGMA foreign_keys = ON;
```

异常策略：

- 读失败：展示错误书。
- 写失败：保留原版书或写入失败恢复队列。
- 数据缺失：展示“书籍数据缺失，请联系管理员”。
- 数据库损坏：启动时禁用转换功能，只允许恢复和诊断。

## 13. 指令计划

MVP 指令：

- `/booklite reload`
- `/booklite convert`
- `/booklite restore`
- `/booklite read <id>`
- `/booklite delete <id>`
- `/booklite list [page]`
- `/booklite status`

第二阶段指令：

- `/booklite undelete <id>`
- `/booklite purge`
- `/booklite restorecontainer`
- `/booklite scanloaded`
- `/booklite export <id>`
- `/booklite import`

权限建议：

- `booklite.use`
- `booklite.convert`
- `booklite.restore`
- `booklite.admin`
- `booklite.admin.read`
- `booklite.admin.delete`
- `booklite.admin.restorecontainer`

## 14. 配置计划

```yaml
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
  reject_unresolved_selectors: false

behavior:
  auto_convert_signed_books: true
  allow_crafting_copy: true
  preserve_generation: true
  deleted_book_message: "这本书已被管理员删除。"
  missing_book_message: "这本书的数据缺失，请联系管理员。"

lectern:
  enabled: false
  blank_page_placeholder: ""

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

## 15. 开发阶段

### 阶段 0：项目骨架

- 创建 Paper 插件工程。
- 确定最低 MC / Paper 版本。
- 接入 SQLite 驱动。
- 初始化配置、日志、指令框架。
- 建立数据库迁移系统。

验收：

- 插件能启动、生成配置、创建数据库表。
- `/booklite status` 可显示基础状态。

### 阶段 1：核心转换闭环

- 实现书籍内容提取。
- 实现数据库保存与读取。
- 实现空壳书 PDC。
- 实现 `/booklite convert`。
- 实现 `/booklite restore`。

验收：

- 原版书转换后物品数据明显减小。
- 恢复后标题、作者、页数、颜色、点击事件、generation 保持一致。

### 阶段 2：签书自动接管与阅读

- 监听签书事件。
- 自动保存并替换为空壳书。
- 监听右键阅读。
- 构造虚拟完整书并打开。
- 加入缓存。

验收：

- 玩家签书后得到 BookLite 空壳书。
- 右键阅读体验接近原版。
- 数据库关闭或内容缺失时有友好降级。

### 阶段 3：复制与去重

- 实现工作台复制拦截。
- 复用 `book_id`。
- 正确处理 `generation`。
- 加入内容 hash 去重。

验收：

- 复制 100 本相同书，数据库正文只保存一份。
- 副本 generation 符合原版规则。
- 删除原书内容后所有副本打开显示删除提示。

### 阶段 4：管理功能

- `/booklite list`
- `/booklite read <id>`
- `/booklite delete <id>`
- `/booklite undelete <id>`
- 管理日志。

验收：

- 管理员能审查数据库中的书。
- 删除后对应虚拟书无法继续显示原内容。

### 阶段 5：卸载准备模式

- 实现 `uninstall.mode`。
- 玩家上线背包被动恢复。
- 打开容器被动恢复。
- 限速恢复队列。
- `/booklite restorecontainer`。

验收：

- 开启卸载模式后，新书不再接管。
- 玩家常用物品会逐步转回原版书。
- 恢复过程不会造成明显 TPS 波动。

### 阶段 6：讲台兼容

- 实现基础讲台放入、读取、取出。
- 验证比较器输出。
- 验证重启和区块卸载。
- 根据结果决定是否标注“完整兼容”或“阅读兼容”。

验收：

- 玩家可从讲台阅读 BookLite 书。
- 取下后不会丢失 PDC。
- 红石行为有明确测试结论。

## 16. 测试计划

单元测试：

- 书籍序列化 / 反序列化。
- JSON 页面保真。
- hash 去重。
- generation 递增。
- 数据库迁移。

集成测试：

- 签书后转换为空壳书。
- 右键打开虚拟书。
- 复制书。
- restore 回原版书。
- delete 后读取提示。
- 插件重启后数据仍可读。

压力测试：

- 单箱 27 个潜影盒，每个潜影盒装满大书。
- 玩家登录携带大量虚拟书。
- 多玩家同时阅读同一本书。
- 大量复制同一本书。
- 卸载模式下打开大量容器。

兼容测试：

- Paper 最新稳定版。
- Folia 如计划支持，需要单独适配调度器。
- ViaVersion / Geyser 服务器只做非承诺测试。
- 与常见背包、保护、菜单、商店插件共存。

## 17. 风险清单

高风险：

- 签书事件中异步写库失败导致空壳书无数据。
- 使用自增 ID 导致主线程拿不到稳定 ID。
- 复制时共用完整 PDC 导致 generation 不正确。
- 讲台翻页与红石输出无法通过 `openBook()` 完整模拟。
- 不同 MC 版本中 Written Book 数据组件 API 变化。

中风险：

- SQLite 写锁导致偶发延迟。
- 玩家快速切换物品后异步回调改错物品。
- 软删除后管理员误以为数据已物理删除。
- 其他插件修改 BookMeta 或 PDC 造成冲突。

低风险：

- 缓存过小导致频繁查库。
- list 指令分页体验差。
- tooltip 显示信息不足。

## 18. 发布策略

建议先发布为 beta：

- 明确支持版本。
- 明确讲台兼容程度。
- 明确数据会保存到 SQLite。
- 明确卸载前必须开启卸载模式或使用 restore。
- 提供备份建议。

README 卖点建议：

- “让玩家放心建大型图书馆”
- “书籍内容数据库化”
- “同内容副本去重”
- “可无损还原回原版书”
- “管理员可审查与删除违规书籍”

不要过度承诺：

- 不承诺阻止所有未知书本漏洞。
- 不承诺所有红石讲台机器 100% 原版一致，除非测试证实。
- 不承诺跨版本降级。

## 19. 最终成功标准

BookLite 成功的标准不是“能把书打开”，而是：

- 大量书籍长期存放时，容器和区块数据明显变轻。
- 玩家正常写书、读书、复制书时几乎感觉不到插件存在。
- 管理员能找回、审查、删除、恢复书籍。
- 插件被移除前，服务器能平滑转回原版书，不丢数据。
- 与现有 PacketBooks 相比，BookLite 提供更强的管理、去重、图书馆场景和退出体验。
