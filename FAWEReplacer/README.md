# FAWEReplace

> **[中文](#中文文档) | [English](README_EN.md)**

---

## 中文文档

### 简介

**FAWEReplace** 是一个面向大型 Minecraft 世界清理任务的高性能 Paper 插件，基于 FastAsyncWorldEdit (FAWE) 的异步能力实现高效的批量方块替换与实体移除。

**版本**: 1.0.4  
**支持**: Minecraft 1.20.2+, Paper/Spigot  
**依赖**: FastAsyncWorldEdit (FAWE)

### ✨ 核心特性

- 🌍 **批量方块替换** - 支持数百种方块类型的批量替换操作
- 🚀 **高性能异步处理** - 基于 FAWE 异步能力，支持多线程并行处理
- 📦 **懒加载切片调度** - 无需一次性生成数百万个任务，可直接处理 16w×16w 等超大区域
- 💾 **断点续跑** - 服务器中断后可根据进度记录自动恢复，继续处理未完成的切片
- 🎭 **实体清理** - 可选的实体类型清理功能（掉落物、怪物、盔甲架等）
- 🌐 **多语言支持** - 完整的中文/英文双语支持
- 🛡️ **内存保护** - 自动监控内存使用，防止 OutOfMemoryError
- 🔧 **智能区块修复** - 自动修复高度图，防止 MCA Selector 中出现黑色区块
- 📊 **实时进度监控** - 实时进度与详细日志输出，包含替换数量和实体统计
- ⚙️ **灵活配置** - 支持在线服务器和离线处理两种模式，可根据需求调整性能参数ace

FAWEReplace 是一个面向大型世界清理任务的 Paper 插件，依托 FastAsyncWorldEdit (FAWE) 的异步能力实现高效的批量替换与实体移除。本项目目前内置一套面向“垃圾方块回收/服务器清理”的工作流：

- 多种方块的批量替换（通过配置分组，统一替换为目标方块）
- 按指定区域拆分为切片并异步处理，支持自定义并行度
- **懒加载切片调度**：无需一次性生成数百万个任务，可直接覆盖 16w×16w 等超大区域
- **断点续跑**：服务器中断后可根据进度记录自动恢复，继续清理尚未完成的切片
- 可选实体清理（支持自定义实体类型名单）
- 实时进度与日志输出，包含替换数量和实体统计

### 🚀 快速开始

#### 1. 安装插件

**方式一：从 Releases 下载**
```bash
# 下载预编译的 JAR 文件
# 将 FAWEReplace-1.0.3-all.jar 放入服务器 plugins/ 目录
```

**方式二：从源码构建**
```bash
./gradlew clean build
# 生成的 build/libs/FAWEReplace-1.0.3-all.jar 放入服务器 plugins/ 目录
```

#### 2. 配置插件

启动服务器后，编辑 `plugins/FAWEReplace/config.yml`：

```yaml
# 选择语言
language: zh_CN  # 或 en_US

# 设置目标世界和区域
world: "world"
target:
  start:
    x: -1000
    z: -1000
  end:
    x: 1000
    z: 1000

# 配置要替换的方块
blocks:
  - origin: SHULKER_BOX
    target: AIR
  - origin: CHEST
    target: AIR

# 开启自动修复黑色区块
auto-fix-heightmap: true

# 根据使用场景选择配置
# 🔵 在线服务器（安全稳定）
parallel: 2
delay-between-batches-ms: 100

# 🟢 离线处理（最大速度）
# parallel: 8
# delay-between-batches-ms: 0
```

#### 3. 执行命令

```bash
/fawereplace start   # 启动清理任务
/fawereplace stop    # 停止任务并保存进度
/fawereplace status  # 查看当前进度
/fawereplace reload  # 重载配置和语言文件
```

**权限节点**: `fawereplace.use`

### 📋 配置说明

#### 核心配置选项

| 配置项 | 类型 | 说明 |
|--------|------|------|
| `language` | string | 语言设置：`zh_CN` (中文) 或 `en_US` (英文) |
| `world` | string | 目标世界名称 |
| `parallel` | int | 并行处理线程数，建议根据内存和 CPU 核心数设置 |
| `target.start/end` | 坐标 | 需要清理的区域范围（x, z, y 坐标） |
| `region.x/z/y` | int | 单个切片的尺寸，`region.y` 可省略以覆盖全部高度 |
| `auto-fix-heightmap` | bool | **[新]** 自动修复区块高度图，防止黑色区块问题 |
| `skip-ungenerated-chunks` | bool | 跳过未生成的区块，避免区块加载错误 |

#### 性能调优选项

| 配置项 | 类型 | 说明 |
|--------|------|------|
| `performance.delay-between-batches-ms` | long | 批次间延迟（毫秒），用于降低服务器负载 |
| `performance.delay-between-chunks-ms` | long | 区块间延迟（毫秒） |
| `performance.gc-every-chunks` | int | 每处理 N 个区块执行垃圾回收（0=禁用） |

#### 内存保护选项

| 配置项 | 类型 | 说明 |
|--------|------|------|
| `memory-protection.enabled` | bool | 启用内存保护机制 |
| `memory-protection.min-free-memory-percent` | double | 最小可用内存百分比（0.0-1.0） |
| `memory-protection.wait-on-low-memory-ms` | long | 内存不足时等待时间（毫秒） |
| `memory-protection.max-memory-retries` | int | 内存不足时最大重试次数 |

#### 断点续跑选项

| 配置项 | 类型 | 说明 |
|--------|------|------|
| `resume.enabled` | bool | 启用断点续跑功能 |
| `resume.file` | string | 进度文件名称（默认 `progress.yml`） |
| `resume.save-every` | int | 每处理多少切片保存一次进度 |

#### 方块和实体清理

| 配置项 | 类型 | 说明 |
|--------|------|------|
| `blocks` | list | 方块替换规则列表（origin → target） |
| `entities.enabled` | bool | 启用实体清理功能 |
| `entities.types` | list | 要清理的实体类型列表（如 ITEM, ZOMBIE） |

### 🎯 配置预设推荐

#### 🔵 在线服务器（安全稳定）
```yaml
parallel: 2-3
delay-between-batches-ms: 100
delay-between-chunks-ms: 20
gc-every-chunks: 50
min-free-memory-percent: 0.20
auto-fix-heightmap: true
```

#### 🟢 离线高性能处理（最大速度）
```yaml
parallel: 8-12  # 根据 RAM 调整
delay-between-batches-ms: 0
delay-between-chunks-ms: 0
gc-every-chunks: 0
min-free-memory-percent: 0.12
auto-fix-heightmap: true
```

#### 🟡 平衡配置（性能与稳定兼顾）
```yaml
parallel: 4
delay-between-batches-ms: 50
delay-between-chunks-ms: 10
gc-every-chunks: 0
min-free-memory-percent: 0.15
auto-fix-heightmap: true
```

> **💡 提示**: 16w×16w 大型地图建议使用 `region.x/z = 512`、并行度 4-8、启用 `resume.enabled = true`

### 💾 断点续跑 & 日志系统

#### 进度保存机制

- **进度文件位置**: `plugins/FAWEReplace/progress.yml`
- **保存时机**: 
  - 执行 `/fawereplace stop` 时强制保存
  - 运行过程中按 `resume.save-every` 频率自动保存
  - 任务完成后自动删除进度文件
- **保存内容**: 当前区域、切片尺寸、已完成切片数、替换统计

#### 断点恢复

1. 保持 `resume.enabled = true`
2. 任务中断后确保 `progress.yml` 存在
3. 再次执行 `/fawereplace start`
4. 插件会验证配置一致性后自动继续

#### 日志文件

- **文件位置**: `plugins/FAWEReplace/clean-log.txt`
- **记录内容**: 
  - 每次任务开始/停止/完成的时间戳
  - 处理的区域范围和切片信息
  - 方块替换数量统计
  - 实体清理数量统计
  - 任务耗时和性能指标

### 🏗️ 技术架构

#### 核心技术特点

- **懒加载切片调度**: 使用原子计数器按需生成切片坐标，避免预先创建数百万个任务对象
- **多线程异步处理**: 通过 FAWE 的 `EditSession` 在工作线程中并行处理方块替换
- **线程安全设计**: 实体操作在主线程调度，方块操作在异步线程，确保数据安全
- **进度持久化**: 切片进度、统计数据实时保存到 YAML 文件，支持断点续跑
- **内存智能管理**: 实时监控内存使用，自动触发 GC 和暂停机制，防止 OOM
- **高度图自动修复**: 处理后自动刷新区块高度图，避免渲染问题

#### 安全保障机制

1. **三层区块安全检查**:
   - 检查区块是否已生成
   - 过滤已加载的区块
   - 验证 TileEntity 兼容性

2. **数据完整性保护**:
   - 配置一致性验证
   - 进度文件校验
   - 异常恢复机制

3. **性能监控**:
   - 内存使用率监控
   - 处理速度统计
   - 自动节流机制

### 🛠️ 开发与构建

#### 开发环境要求

- **Java**: 17 或更高版本
- **构建工具**: Gradle (已内置 Wrapper)
- **IDE**: IntelliJ IDEA / Eclipse / VS Code

#### 构建命令

```bash
# 清理并构建项目
./gradlew clean build

# 运行本地测试服务器
./gradlew runServer

# 仅编译（不打包）
./gradlew compileJava

# 生成 Shadow JAR（包含依赖）
./gradlew shadowJar
```

#### 项目结构

```
src/main/java/org/cubexmc/fawereplace/
├── FAWEReplace.java              # 主插件类
├── LanguageManager.java          # 多语言管理器
├── commands/
│   ├── FaweReplaceCommand.java   # 命令执行器
│   └── FaweReplaceTabCompleter.java  # Tab 补全
└── tasks/
    ├── CleaningTask.java         # 核心清理任务
    └── ChunkRepairTask.java      # 区块修复工具

src/main/resources/
├── config.yml                    # 配置文件
├── plugin.yml                    # 插件元数据
├── paper-plugin.yml              # Paper 插件信息
└── lang/
    ├── zh_CN.yml                 # 中文语言文件
    └── en_US.yml                 # 英文语言文件
```

#### 贡献指南

欢迎提交 Issue 和 Pull Request！在提交 PR 前请确保：

1. 代码遵循项目现有风格
2. 添加必要的注释和文档
3. 测试通过且无明显 Bug
4. 更新相关文档（如有必要）

### ❓ 常见问题

#### Q1: 如何从中断处继续？

**答**: 
1. 确保配置中 `resume.enabled = true`
2. 停止任务后检查 `plugins/FAWEReplace/progress.yml` 是否存在
3. 再次执行 `/fawereplace start`
4. 插件会自动验证配置并继续处理

#### Q2: 遇到黑色区块（在 MCA Selector 中显示）怎么办？

**答**: 这是高度图损坏导致的。解决方法：
1. 在配置中启用 `auto-fix-heightmap: true`（推荐）
2. 或使用修复脚本：`scripts/fix_black_chunks.bat` (Windows) 或 `scripts/fix_black_chunks.sh` (Linux)
3. 查看 `BLACK_CHUNKS_SOLUTION.md` 获取详细说明

#### Q3: 服务器内存不足/卡顿怎么办？

**答**: 
1. 降低 `parallel` 值（建议 2-4）
2. 增加 `delay-between-batches-ms`（建议 50-100）
3. 设置 `min-free-memory-percent: 0.20` 或更高
4. 启用 `gc-every-chunks: 50`
5. 查看 `PERFORMANCE_GUIDE.md` 获取优化建议

#### Q4: 如何只清理掉落物不清理方块？

**答**: 
```yaml
blocks: []  # 清空方块替换列表
entities:
  enabled: true
  types:
    - ITEM
    - ARROW
```

#### Q5: 支持分布式处理吗？

**答**: 支持！查看以下文档：
- `DISTRIBUTED_QUICKSTART.md` - 快速入门
- `DISTRIBUTED_PROCESSING_GUIDE.md` - 详细指南
- 使用 `scripts/split_world.sh` 和 `scripts/merge_worlds.sh` 工具

#### Q6: 会误删玩家建筑的区块吗？

**答**: 不会。插件有三层安全保护：
1. 只处理已生成的区块
2. 跳过当前加载的区块
3. 验证 TileEntity 兼容性
详见 `CHUNK_SAFETY_ANALYSIS.md`

#### Q7: 大范围清实体或清容器物品应该在线跑吗？

**答**: 不建议。大范围实体清理和容器物品清理更适合关服后离线处理：
1. 在线 `/fawereplace start` 主要用于方块替换
2. 离线清实体使用 `tools/clean_entities_mca.py --mode entities`
3. 离线清容器物品使用 `tools/clean_entities_mca.py --mode containers`
4. 详见 `tools/OFFLINE_CLEANING.md`

### 📚 完整文档

- **[README.md](README.md)** - 本文档
- **[tools/OFFLINE_CLEANING.md](tools/OFFLINE_CLEANING.md)** - 离线实体与容器清理说明
- **[PERFORMANCE_GUIDE.md](PERFORMANCE_GUIDE.md)** - 性能优化完整指南
- **[BLACK_CHUNKS_SOLUTION.md](BLACK_CHUNKS_SOLUTION.md)** - 黑色区块问题详解
- **[LANGUAGE_SUPPORT.md](LANGUAGE_SUPPORT.md)** - 多语言功能说明
- **[DISTRIBUTED_QUICKSTART.md](DISTRIBUTED_QUICKSTART.md)** - 分布式处理快速入门
- **[DISTRIBUTED_PROCESSING_GUIDE.md](DISTRIBUTED_PROCESSING_GUIDE.md)** - 分布式处理详细指南
- **[SAFETY_QUICK_REFERENCE.md](SAFETY_QUICK_REFERENCE.md)** - 安全性快速参考
- **[CHUNK_SAFETY_ANALYSIS.md](CHUNK_SAFETY_ANALYSIS.md)** - 区块安全性详细分析
- **[CONFIG_COMPARISON.md](CONFIG_COMPARISON.md)** - 配置对比说明

### 📝 许可证

本项目采用 MIT 许可证。详见 [LICENSE](LICENSE) 文件。

### 🤝 贡献者

- **cong0707** - 初始开发
- **angushushu** - 功能增强、多语言支持、性能优化

### 💬 支持与反馈

- **GitHub Issues**: [提交问题](https://github.com/CubeX-MC/FAWEReplacer/issues)
- **查看日志**: `plugins/FAWEReplace/clean-log.txt`
- **调试模式**: 在配置中启用详细日志
