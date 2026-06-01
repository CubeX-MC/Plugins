# FAWEReplace

> **High-Performance Minecraft World Cleanup Plugin**
>
> **[English](README_EN.md) | [中文](README.md)**

---

## Introduction

**FAWEReplace** is a high-performance Paper plugin designed for large-scale Minecraft world cleanup tasks. Built on FastAsyncWorldEdit (FAWE)'s asynchronous capabilities, it efficiently handles bulk block replacement and entity removal operations.

**Version**: 1.0.3  
**Release Date**: October 7, 2025  
**Supports**: Minecraft 1.20.2+, Paper/Spigot  
**Requires**: FastAsyncWorldEdit (FAWE)

## ✨ Key Features

- 🌍 **Bulk Block Replacement** - Support for hundreds of block types with batch operations
- 🚀 **High-Performance Async Processing** - Multi-threaded parallel processing based on FAWE
- 📦 **Lazy-Loading Tile Scheduling** - Handle 16w×16w regions without pre-generating millions of tasks
- 💾 **Resume from Checkpoint** - Auto-recovery after server interruption with progress tracking
- 🎭 **Entity Cleanup** - Optional entity type removal (dropped items, mobs, armor stands, etc.)
- 🌐 **Multi-Language Support** - Complete Chinese/English bilingual support
- 🛡️ **Memory Protection** - Auto-monitoring to prevent OutOfMemoryError
- 🔧 **Smart Chunk Repair** - Auto-fix heightmaps to prevent black chunks in MCA Selector
- 📊 **Real-time Progress Monitoring** - Live progress and detailed logs with replacement statistics
- ⚙️ **Flexible Configuration** - Support for both online server and offline processing modes

## 🚀 Quick Start

### 1. Install the Plugin

**Option A: Download from Releases**
```bash
# Download the pre-built JAR file from GitHub Releases
# Place FAWEReplace-1.0.3-all.jar in your server's plugins/ directory
```

**Option B: Build from Source**
```bash
./gradlew clean build
# Place the generated build/libs/FAWEReplace-1.0.3-all.jar in plugins/ directory
```

### 2. Configure the Plugin

After starting the server, edit `plugins/FAWEReplace/config.yml`:

```yaml
# Choose language
language: en_US  # or zh_CN for Chinese

# Set target world and region
world: "world"
target:
  start:
    x: -1000
    z: -1000
  end:
    x: 1000
    z: 1000

# Configure blocks to replace
blocks:
  - origin: SHULKER_BOX
    target: AIR
  - origin: CHEST
    target: AIR

# Enable auto-fix for black chunks
auto-fix-heightmap: true

# Choose configuration based on your use case
# 🔵 Online Server (Safe & Stable)
parallel: 2
delay-between-batches-ms: 100

# 🟢 Offline Processing (Maximum Speed)
# parallel: 8
# delay-between-batches-ms: 0
```

### 3. Execute Commands

```bash
/fawereplace start   # Start cleanup task
/fawereplace stop    # Stop and save progress
/fawereplace status  # View current progress
/fawereplace reload  # Reload config and language files
```

**Permission**: `fawereplace.use`

## 📋 Configuration Guide

### Core Options

| Option | Type | Description |
|--------|------|-------------|
| `language` | string | Language: `zh_CN` (Chinese) or `en_US` (English) |
| `world` | string | Target world name |
| `parallel` | int | Parallel processing threads, adjust based on RAM and CPU cores |
| `target.start/end` | coords | Region to clean (x, z, y coordinates) |
| `region.x/z/y` | int | Tile size, `region.y` optional to cover full height |
| `auto-fix-heightmap` | bool | **[New]** Auto-fix chunk heightmaps to prevent black chunks |
| `skip-ungenerated-chunks` | bool | Skip ungenerated chunks to avoid loading errors |

### Performance Tuning

| Option | Type | Description |
|--------|------|-------------|
| `performance.delay-between-batches-ms` | long | Delay between batches (ms) to reduce server load |
| `performance.delay-between-chunks-ms` | long | Delay between chunks (ms) |
| `performance.gc-every-chunks` | int | Run GC every N chunks (0=disabled) |

### Memory Protection

| Option | Type | Description |
|--------|------|-------------|
| `memory-protection.enabled` | bool | Enable memory protection |
| `memory-protection.min-free-memory-percent` | double | Minimum free memory ratio (0.0-1.0) |
| `memory-protection.wait-on-low-memory-ms` | long | Wait time when low on memory (ms) |
| `memory-protection.max-memory-retries` | int | Max retries when low on memory |

### Resume/Checkpoint

| Option | Type | Description |
|--------|------|-------------|
| `resume.enabled` | bool | Enable resume from checkpoint |
| `resume.file` | string | Progress filename (default `progress.yml`) |
| `resume.save-every` | int | Save progress every N tiles |

### Blocks and Entities

| Option | Type | Description |
|--------|------|-------------|
| `blocks` | list | Block replacement rules (origin → target) |
| `entities.enabled` | bool | Enable entity cleanup |
| `entities.types` | list | Entity types to remove (e.g., ITEM, ZOMBIE) |

## 🎯 Configuration Presets

### 🔵 Online Server (Safe & Stable)
**Recommended for servers with players online**

```yaml
parallel: 2-3
delay-between-batches-ms: 100
delay-between-chunks-ms: 20
gc-every-chunks: 50
min-free-memory-percent: 0.20
auto-fix-heightmap: true
skip-ungenerated-chunks: true
```

**Expected Performance**: ~1,000-2,000 chunks/sec

### 🟢 Offline High-Performance (Maximum Speed)
**Recommended for offline world processing**

```yaml
parallel: 8-12  # Adjust based on available RAM
delay-between-batches-ms: 0
delay-between-chunks-ms: 0
gc-every-chunks: 0
min-free-memory-percent: 0.12
auto-fix-heightmap: true
skip-ungenerated-chunks: true
```

**Expected Performance**: ~2,000-4,000 chunks/sec  
**Speed Improvement**: 20-40% faster than default

### 🟡 Balanced (Good Performance & Safety)
**Recommended for general use**

```yaml
parallel: 4
delay-between-batches-ms: 50
delay-between-chunks-ms: 10
gc-every-chunks: 0
min-free-memory-percent: 0.15
auto-fix-heightmap: true
skip-ungenerated-chunks: true
```

**Expected Performance**: ~1,500-3,000 chunks/sec

> **💡 Tip**: For large 16w×16w maps, use `region.x/z = 512`, parallel 4-8, and enable `resume.enabled = true`

## 💾 Resume & Logging System

### Progress Saving

- **Location**: `plugins/FAWEReplace/progress.yml`
- **Save Triggers**: 
  - Manual: `/fawereplace stop` command
  - Automatic: Every `resume.save-every` tiles processed
  - Auto-delete: After task completion
- **Contents**: Current region, tile size, completed tiles, replacement statistics

### Resume from Checkpoint

1. Keep `resume.enabled = true` in config
2. Ensure `progress.yml` exists after interruption
3. Execute `/fawereplace start` again
4. Plugin verifies config consistency and continues from last checkpoint

### Log File

- **Location**: `plugins/FAWEReplace/clean-log.txt`
- **Contents**: 
  - Start/stop/completion timestamps
  - Processed region and tile information
  - Block replacement statistics
  - Entity cleanup statistics
  - Task duration and performance metrics

## 🏗️ Technical Architecture

### Core Features

- **Lazy-Loading Tile Scheduling**: Uses atomic counters to generate tile coordinates on-demand, avoiding memory overhead from pre-generating millions of task objects
- **Multi-threaded Async Processing**: Parallel block replacement via FAWE's `EditSession` in worker threads
- **Thread-Safe Design**: Entity operations on main thread, block operations async for data safety
- **Progress Persistence**: Real-time progress and statistics saved to YAML for resume capability
- **Smart Memory Management**: Real-time monitoring with auto-GC and pause mechanisms to prevent OOM
- **Auto Heightmap Repair**: Refreshes chunk heightmaps after processing to avoid rendering issues

### Safety Mechanisms

#### 1. Three-Layer Chunk Safety
- **Layer 1**: Check if chunk is generated before processing
- **Layer 2**: Filter out currently loaded chunks
- **Layer 3**: Verify TileEntity compatibility

#### 2. Data Integrity Protection
- Config consistency validation before resume
- Progress file verification
- Exception recovery mechanisms

#### 3. Performance Monitoring
- Real-time memory usage tracking
- Processing speed statistics
- Auto-throttling when resources are constrained

## 🛠️ Development & Building

### Requirements

- **Java**: 17 or higher
- **Build Tool**: Gradle (Wrapper included)
- **IDE**: IntelliJ IDEA / Eclipse / VS Code (recommended)

### Build Commands

```bash
# Clean and build project
./gradlew clean build

# Run local test server
./gradlew runServer

# Compile only (no packaging)
./gradlew compileJava

# Generate Shadow JAR (with dependencies)
./gradlew shadowJar
```

### Project Structure

```
src/main/java/org/cubexmc/fawereplace/
├── FAWEReplace.java              # Main plugin class
├── LanguageManager.java          # Multi-language manager
├── commands/
│   ├── FaweReplaceCommand.java   # Command executor
│   └── FaweReplaceTabCompleter.java  # Tab completion
└── tasks/
    ├── CleaningTask.java         # Core cleanup task
    └── ChunkRepairTask.java      # Chunk repair utility

src/main/resources/
├── config.yml                    # Configuration file
├── plugin.yml                    # Plugin metadata
├── paper-plugin.yml              # Paper plugin info
└── lang/
    ├── zh_CN.yml                 # Chinese language file
    └── en_US.yml                 # English language file
```

### Contributing

We welcome Issues and Pull Requests! Before submitting a PR, please ensure:

1. ✅ Code follows the project's existing style
2. ✅ Add necessary comments and documentation
3. ✅ Tests pass without obvious bugs
4. ✅ Update relevant documentation (if necessary)

## ❓ Frequently Asked Questions

### Q1: How to resume from interruption?

**Answer**: 
1. Ensure `resume.enabled = true` in config
2. Check that `plugins/FAWEReplace/progress.yml` exists after stopping
3. Execute `/fawereplace start` again
4. Plugin auto-verifies config and continues from last saved position

---

### Q2: Seeing black chunks in MCA Selector?

**Answer**: This is caused by corrupted heightmaps after block replacement. 

**Solutions**:
1. **Automatic Fix (Recommended)**: Enable `auto-fix-heightmap: true` in config
2. **Manual Repair**: Use repair scripts:
   - Windows: `scripts/fix_black_chunks.bat`
   - Linux/Mac: `scripts/fix_black_chunks.sh`
3. **Detailed Info**: See `BLACK_CHUNKS_SOLUTION.md` for technical explanation

---

### Q3: Server running out of memory or lagging?

**Answer**: Optimize your configuration:

1. **Reduce parallel processing**: Set `parallel: 2-4`
2. **Add delays**: Set `delay-between-batches-ms: 50-100`
3. **Increase memory threshold**: Set `min-free-memory-percent: 0.20` or higher
4. **Enable periodic GC**: Set `gc-every-chunks: 50`
5. **Check documentation**: See `PERFORMANCE_GUIDE.md` for detailed optimization tips

**JVM Arguments (Recommended)**:
```bash
java -Xms5G -Xmx5G -XX:+UseG1GC -XX:+ParallelRefProcEnabled \
     -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions \
     -XX:+DisableExplicitGC -XX:G1NewSizePercent=30 \
     -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M \
     -XX:G1ReservePercent=20 -jar paper.jar nogui
```

---

### Q4: How to clean only entities, not blocks?

**Answer**: 
```yaml
blocks: []  # Empty block replacement list

entities:
  enabled: true
  types:
    - ITEM          # Dropped items
    - ARROW         # Arrows
    - EXPERIENCE_ORB  # XP orbs
```

---

### Q5: Does it support distributed processing?

**Answer**: **Yes!** FAWEReplace supports distributed processing for extremely large worlds.

**Quick Start**:
1. Split your world into regions using `scripts/split_world.sh`
2. Process each region on different machines
3. Merge the results using `scripts/merge_worlds.sh`

**Documentation**:
- `DISTRIBUTED_QUICKSTART.md` - Quick start guide
- `DISTRIBUTED_PROCESSING_GUIDE.md` - Detailed distributed processing guide

**Example**:
```bash
# Machine 1: Process region 1
./split_world.sh world region1 -8000 -8000 0 0

# Machine 2: Process region 2
./split_world.sh world region2 0 -8000 8000 0

# After processing, merge on main server
./merge_worlds.sh world region1 region2
```

---

### Q6: Will it delete player-built chunks?

**Answer**: **No, it's completely safe!** 

The plugin has **three layers of safety protection**:

1. **Generated Chunk Check**: Only processes chunks that are already generated
2. **Loaded Chunk Filter**: Skips chunks that are currently loaded (where players might be)
3. **TileEntity Verification**: Validates block entity compatibility before processing

See `CHUNK_SAFETY_ANALYSIS.md` for detailed technical analysis.

---

### Q7: Can I add custom block types to replace?

**Answer**: Yes! Edit the `blocks` section in `config.yml`:

```yaml
blocks:
  - origin: SHULKER_BOX
    target: AIR
  - origin: YELLOW_SHULKER_BOX
    target: AIR
  - origin: CHEST
    target: STONE
  - origin: FURNACE
    target: COBBLESTONE
```

Use Bukkit Material names. Full list: https://hub.spigotmc.org/javadocs/spigot/org/bukkit/Material.html

---

### Q8: How to process only specific Y levels?

**Answer**: Set Y coordinates in target region:

```yaml
target:
  start:
    x: -1000
    z: -1000
    y: -64      # Start from Y=-64
  end:
    x: 1000
    z: 1000
    y: 100      # End at Y=100
```

This will only process blocks between Y=-64 and Y=100.

---

## 📚 Complete Documentation

### User Guides
- **[README_EN.md](README_EN.md)** - This document (English)
- **[README.md](README.md)** - Chinese version
- **[tools/OFFLINE_CLEANING.md](tools/OFFLINE_CLEANING.md)** - Offline entity and container cleanup
- **[PERFORMANCE_GUIDE.md](PERFORMANCE_GUIDE.md)** - Complete performance optimization guide
- **[BLACK_CHUNKS_SOLUTION.md](BLACK_CHUNKS_SOLUTION.md)** - Black chunks issue explained
- **[LANGUAGE_SUPPORT.md](LANGUAGE_SUPPORT.md)** - Multi-language feature documentation

### Advanced Topics
- **[DISTRIBUTED_QUICKSTART.md](DISTRIBUTED_QUICKSTART.md)** - Distributed processing quickstart
- **[DISTRIBUTED_PROCESSING_GUIDE.md](DISTRIBUTED_PROCESSING_GUIDE.md)** - Detailed distributed processing guide
- **[CHUNK_SAFETY_ANALYSIS.md](CHUNK_SAFETY_ANALYSIS.md)** - Detailed chunk safety analysis
- **[CONFIG_COMPARISON.md](CONFIG_COMPARISON.md)** - Configuration comparison and examples

### Quick References
- **[SAFETY_QUICK_REFERENCE.md](SAFETY_QUICK_REFERENCE.md)** - Safety quick reference

## 🔧 Utility Scripts

Located in `scripts/` directory:

### Chunk Repair
- **`fix_black_chunks.bat`** (Windows) - Repair black chunks
- **`fix_black_chunks.sh`** (Linux/Mac) - Repair black chunks

### World Management
- **`split_world.ps1`** / **`split_world.sh`** - Split world into regions for distributed processing
- **`merge_worlds.ps1`** / **`merge_worlds.sh`** - Merge processed regions back together

## 🎮 Usage Examples

### Example 1: Clean Shulker Boxes in Spawn Area

```yaml
world: "world"
target:
  start: { x: -500, z: -500 }
  end: { x: 500, z: 500 }
blocks:
  - origin: SHULKER_BOX
    target: AIR
  - origin: YELLOW_SHULKER_BOX
    target: AIR
parallel: 4
auto-fix-heightmap: true
```

### Example 2: Remove All Entities Except Players

```yaml
world: "world"
target:
  start: { x: -10000, z: -10000 }
  end: { x: 10000, z: 10000 }
blocks: []  # Don't replace any blocks
entities:
  enabled: true
  types:
    - ITEM
    - ARROW
    - ZOMBIE
    - SKELETON
    - CREEPER
```

### Example 3: High-Performance Offline Processing

```yaml
world: "world_nether"
parallel: 12
delay-between-batches-ms: 0
delay-between-chunks-ms: 0
gc-every-chunks: 0
min-free-memory-percent: 0.10
auto-fix-heightmap: true
resume:
  enabled: true
  save-every: 100
```

## 🚦 Performance Tips

### Memory Allocation

**Recommended RAM allocation based on parallel threads**:

| Parallel Threads | Minimum RAM | Recommended RAM |
|------------------|-------------|-----------------|
| 2-3              | 2 GB        | 4 GB            |
| 4-6              | 4 GB        | 8 GB            |
| 8-12             | 8 GB        | 16 GB           |
| 12+              | 16 GB       | 32 GB           |

### JVM Tuning

**For 8GB Server**:
```bash
java -Xms8G -Xmx8G \
     -XX:+UseG1GC \
     -XX:+ParallelRefProcEnabled \
     -XX:MaxGCPauseMillis=200 \
     -XX:+UnlockExperimentalVMOptions \
     -XX:+DisableExplicitGC \
     -XX:G1NewSizePercent=30 \
     -XX:G1MaxNewSizePercent=40 \
     -XX:G1HeapRegionSize=8M \
     -XX:G1ReservePercent=20 \
     -jar paper.jar nogui
```

### Expected Performance

| Configuration | Chunks/Second | Use Case |
|---------------|---------------|----------|
| Online Server (parallel: 2-3) | 1,000-2,000 | Safe for players online |
| Balanced (parallel: 4-6) | 1,500-3,000 | General purpose |
| Offline High-Perf (parallel: 8-12) | 2,000-4,000 | Maximum speed |

### Optimization Checklist

- ✅ Use offline processing mode for best performance
- ✅ Set `delay-between-batches-ms: 0` for offline processing
- ✅ Disable `gc-every-chunks` for maximum speed (if enough RAM)
- ✅ Enable `auto-fix-heightmap: true` to prevent issues
- ✅ Enable `resume.enabled: true` for large jobs
- ✅ Use appropriate `parallel` value based on RAM
- ✅ Monitor server logs for memory warnings

## 📝 License

This project is licensed under the **MIT License**. See [LICENSE](LICENSE) file for details.

### What This Means

- ✅ **Commercial Use**: You can use this plugin on commercial servers
- ✅ **Modification**: You can modify the source code
- ✅ **Distribution**: You can distribute the plugin
- ✅ **Private Use**: You can use it privately
- ⚠️ **Liability**: No warranty provided, use at your own risk

## 🤝 Contributors

- **cong0707** - Initial development and core architecture
- **angushushu** - Feature enhancements, multi-language support, performance optimization

### Want to Contribute?

We welcome contributions! Here's how:

1. 🍴 Fork the repository
2. 🔨 Create a feature branch (`git checkout -b feature/amazing-feature`)
3. 💾 Commit your changes (`git commit -m 'Add amazing feature'`)
4. 📤 Push to the branch (`git push origin feature/amazing-feature`)
5. 🔍 Open a Pull Request

## 💬 Support & Feedback

### Get Help

- **📖 Documentation**: Check the docs listed above
- **🐛 Bug Reports**: [Submit an issue](https://github.com/CubeX-MC/FAWEReplacer/issues)
- **💡 Feature Requests**: [Open a discussion](https://github.com/CubeX-MC/FAWEReplacer/discussions)
- **📝 Logs**: Check `plugins/FAWEReplace/clean-log.txt` for debugging

### Debug Mode

Enable verbose logging in `config.yml`:
```yaml
# Add to config for detailed logging
debug: true
```

### Common Log Locations

- **Plugin logs**: `plugins/FAWEReplace/clean-log.txt`
- **Server logs**: `logs/latest.log`
- **Progress file**: `plugins/FAWEReplace/progress.yml`

## 🌟 Acknowledgments

- **FastAsyncWorldEdit (FAWE)** - For providing the powerful async block editing API
- **Paper/Spigot** - For the excellent server platform
- **WorldEdit** - For the foundational world editing tools
- **Minecraft Community** - For feedback and support

## 📊 Statistics

- **Lines of Code**: ~2,000+
- **Supported Block Types**: 700+
- **Supported Entity Types**: 100+
- **Languages**: 2 (Chinese, English)
- **Documentation Files**: 9
- **Utility Scripts**: 4

---

**Made with ❤️ for the Minecraft community**

*Last Updated: October 7, 2025*
