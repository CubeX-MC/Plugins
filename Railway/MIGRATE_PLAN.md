# Railway Migration Plan

## Fork Strategy

Railway is a fork of [Metro](https://github.com/CubeX-MC/Metro) (v1.1.6).

- **Package**: `org.cubexmc.metro` (identical to upstream — no rename)
- **Main class**: `org.cubexmc.metro.Metro` (same class name as upstream)
- **Plugin identity**: `plugin.yml` name=**Railway**, `pom.xml` artifactId=**railway**
- **Changes vs Metro**: Only 11 files differ (pom.xml, plugin.yml, 9 permission references)

### Sync with upstream

```bash
git fetch upstream
git checkout main
git merge upstream/main          # clean merge, ~zero conflicts
git checkout railway
git merge main                   # brings upstream changes into railway branch
```

### Branches

| Branch | Purpose |
|--------|---------|
| `main` | Clean Metro fork base — only plugin identity changes. Merge upstream here. |
| `railway` | Railway features stacked on `main`. All new code goes here. |

---

## Completed (Phase 0)

### Fork Base (`main` branch, commit `974f5d4`)
- pom.xml: artifactId=railway, base.name=railway, version=0.3.2
- plugin.yml: name=Railway, version=0.3.2, railway.* permissions
- 9 Java files: `metro.*` → `railway.*` permission string updates

### Railway Utilities (`railway` branch)
| File | Description |
|------|-------------|
| `control/TrainControlMode.java` | KINEMATIC / LEASHED / REACTIVE enum |
| `model/DirectionMode.java` | BI_DIRECTIONAL / CIRCULAR / SINGLE_DIRECTION enum |
| `util/LocationUtil.java` | Enhanced: RailType enum, rail physics helpers (curve detection, safe speed, rail snapping, rail direction) |
| `util/FaceUtil.java` | Block face utilities |
| `util/IntVector3.java` | Integer vector with chunk operations |
| `util/LineTopologyUtil.java` | Line topology analysis |
| `util/MathUtil.java` | Math helpers (lerp, clamping, angles) |
| `util/Quaternion.java` | Quaternion math for rotation |

---

## Pending: Physics & Train Layer (Phase 1) — COMPLETED 2026-05-11

**CRITICAL**: The physics/train/service layer forms a tightly-coupled cluster.
Incremental file-by-file porting does NOT work — each file imports types from others,
creating an infinite chain of `cannot find symbol` errors. Attempted 2026-05-11.

**Strategy for next attempt**: Copy ALL files from all three packages at once (`physics/*.java`,
`train/*.java` Railway-only, `service/*.java` Railway-only), fix package/class references in bulk,
then add missing config methods to Metro.java as a single batch. Only THEN attempt compilation.

### Pre-Flight Checklist (before copying any files)

All of these must be in place BEFORE copying physics/train/service files:

1. **Metro.java** — add 50+ config getter methods (see section below)
2. **model/Line.java** — add `headwaySeconds`, `dwellTicks`, `trainCars`, `serviceEnabled` fields + getters/setters
3. **manager/LineManager.java** — add `tick()`, `saveLines()`, `saveStops()` stub methods
4. **manager/StopManager.java** — add `tick()` stub method
5. **util/SchedulerUtil.java** — add `getCurrentTick()`, `isFolia()`, `ensureTickCounter()` + `AtomicLong` counter
6. **util/AdventureUtil.java** — port with Spigot-compatible fallbacks (use Bukkit API, not Paper)

### Metro.java Config Methods Needed

```java
// All read from getConfig() with defaults:
config(), getControlMode(), isPhysicsLeadKinematic(), isSafeSpeedMode(),
getPhysicsLookaheadBlocks(), getLeashOffsetY(), getLeashMobTypeRaw(),
getServiceDefaultHeadwaySeconds(), getServiceMetricsLogIntervalTicks(),
getChunkLoadingRadius(), getChunkLoadingUpdateIntervalTicks(),
isChunkLoadingEnabled(), isChunkLoadingOnlyWhenMoving(), getForwardPreloadRadius(),
getLocalActivationRadius(), getLocalRailSearchRadius(), getLocalSpawnMode(),
getLocalVirtualIdleTicks(), getLocalVirtualLookaheadStops(),
isLocalVirtualizationEnabled(), getTrainName(), isTrainNameVisible(),
// Title config (~20 methods)
isArriveStopTitleEnabled(), getArriveStopTitle(), getArriveStopSubtitle(), ...
isDepartureTitleEnabled(), getDepartureTitle(), getDepartureSubtitle(), ...
isWaitingTitleEnabled(), getWaitingTitle(), getWaitingSubtitle(), getWaitingInterval(),
isTerminalStopTitleEnabled(), getTerminalStopTitle(), getTerminalStopSubtitle(), ...
getWaitingActionbar(), getDepartureActionbar(),
getArrivalInitialDelay(), getDepartureInitialDelay(), getDepartureInterval(),
getArrivalNotes(), getDepartureNotes(),
// Sound config
isArrivalSoundEnabled(), isDepartureSoundEnabled(),
// Travel time estimator config
isTravelTimeEnabled(), getDefaultSectionSeconds(), getPriorStrength(),
getOutlierSigma(), getDecayPerDay(), getUnboardedSampleWeight(), isUseUnboardedSamples(),
// Misc
getEntityTypeOverride(), getServiceModeRaw(), getCartSpeed(), getTrainSpacing(),
getServiceHeartbeatIntervalTicks(), getLineServiceManager(),
getTravelTimeEstimator(), getBlockSectionManager()
```

### Dependency Graph

```
Metro.java (main class — needs Railway config methods)
  └── Line.java (needs: headwaySeconds, dwellTicks, trainCars fields)
        └── LineService.java
              ├── LineServiceManager.java
              ├── DispatchStrategy.java
              │     ├── GlobalDispatchStrategy.java
              │     └── LocalDispatchStrategy.java
              └── TrainSpawner.java
                    └── TrainInstance.java
                          ├── TrainConsist.java          (multi-cart consist)
                          ├── TrainNavigator.java         (route navigation)
                          ├── TrainNavigatorDecisions.java
                          ├── TrainPassengerRegistry.java
                          ├── TrainRuntimeDecisions.java
                          ├── TrainStateMath.java
                          ├── PassengerExperience.java    (needs AdventureUtil)
                          ├── ConsistController.java      (cart coupling control)
                          ├── ArrivalHeuristics.java
                          └── TrainPhysicsEngine.java
                                ├── physics/KinematicRailPhysics.java     (19 files)
                                ├── physics/LeashedRailPhysics.java
                                ├── physics/ReactiveRailPhysics.java
                                ├── physics/LeashCoupler.java
                                ├── physics/TrainCartsBridge.java
                                └── physics/RailPathUtil.java
```

### Porting Steps

1. **Add Railway config fields to `Metro.java`**:
   - `isTravelTimeEnabled()`, `getDefaultSectionSeconds()`, `getPriorStrength()`, etc.
   - `getServiceHeartbeatIntervalTicks()`, `getConfigFacade()`

2. **Add Railway fields to `model/Line.java`**:
   - `headwaySeconds` (int) — headway-based dispatch interval
   - `dwellTicks` (int) — station dwell time
   - `trainCars` (int) — number of minecarts per consist

3. **Port service layer** (in order):
   - `service/DispatchStrategy.java` (interface)
   - `service/strategy/GlobalDispatchStrategy.java`
   - `service/strategy/LocalDispatchStrategy.java`
   - `service/LineServiceManager.java`
   - `service/TrainSpawner.java`
   - `service/LineService.java`
   - `service/BlockSectionManager.java`

4. **Port train models** (in order):
   - `train/TrainConsist.java`
   - `train/TrainNavigatorDecisions.java`
   - `train/TrainStateMath.java`
   - `train/TrainRuntimeDecisions.java`
   - `train/TrainNavigator.java`
   - `train/TrainPassengerRegistry.java`
   - `train/TrainInstance.java`
   - `train/ArrivalHeuristics.java`
   - `train/ConsistController.java`
   - `train/PassengerExperience.java`

5. **Port physics engine**:
   - `physics/TrainPhysicsEngine.java` (interface)
   - `physics/KinematicRailPhysics.java`
   - `physics/LeashedRailPhysics.java`
   - `physics/ReactiveRailPhysics.java`
   - All supporting physics files

6. **Port estimation**:
   - `estimation/TravelTimeEstimator.java`

### API Compatibility Notes

- `Bukkit.getCurrentTick()` (Paper 1.19+) — used in LineService.tick().
  Replace with reflection or a tick counter managed by SchedulerUtil.
- `player.sendActionBar(Component)` (Paper 1.18+) — used in PassengerExperience.
  Use Adventure Audience API or Spigot fallback in AdventureUtil.
- `player.clearTitle()` (Paper 1.18+) — use `player.resetTitle()` (Bukkit 1.17+).
- NMS-based `MinecartNmsUtil` — may need version-specific implementations.
  Wrap in reflection with Spigot 1.18+ fallback.

---

## Pending: Secondary Features (Phase 2)

| Module | Description | Priority |
|--------|-------------|----------|
| `placeholder/RailwayPlaceholders.java` | PlaceholderAPI expansion — needs `me.clip.placeholderapi` dependency in pom.xml | Medium |
| `gui/menu/` (7 files) | Menu-based GUI — alternative to Metro's controller/view pattern. Depends on Railway-specific GuiManager methods | Low |
| `storage/` (6 files) | YAML persistence — Metro uses `persistence/` package instead. Could be kept as alternative or removed | Low |
| `config/RailwayConfig.java` | Railway config facade — Metro uses ConfigFacade. Consolidate into one | Low |
| `model/EntityModelController.java` | Visual entity model replacement for minecarts — needs Paper API | Low |

---

## How to Continue (Revised)

1. **Checkout the railway branch** and complete the Pre-Flight Checklist above (add all config methods
   to Metro.java, fields to Line.java, stubs to managers, utility methods to SchedulerUtil).

2. **Copy ALL Phase 1 files at once** from backup:
   ```powershell
   $backup = "C:\Users\Angus\AppData\Local\Temp\opencode\railway_backup\src\main\java\org\cubexmc\railway"
   $dest = "C:\Users\Angus\Desktop\MC server\Railway\src\main\java\org\cubexmc\metro"
   # Copy: physics/*, train/* (only new files), service/* (only new files)
   # Apply package fix: org.cubexmc.railway -> org.cubexmc.metro
   # Apply import fix: import ...Railway -> import ...Metro
   # Strip BOM: [System.IO.File]::ReadAllBytes / WriteAllBytes with UTF8Encoding($false)
   ```

3. **Replace `Bukkit.getCurrentTick()`** with `SchedulerUtil.getCurrentTick()` in all copied files.

4. **Compile and fix errors** — at this point most errors should be about specific config method
   signatures, not missing classes.

5. **Commit working batches** and push to `origin/railway`.

### Reference

- Upstream Metro: https://github.com/CubeX-MC/Metro
- Railway fork:  https://github.com/CubeX-MC/Railway
- Metro v1.1.6 commit: `0f20ac8`
- Phase 1 files backup: `%TEMP%\opencode\railway_backup\`
