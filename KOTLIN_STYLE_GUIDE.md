# CubeX Kotlin Style Guide

This guide is intentionally small. Kotlin migration is behavior-preserving first, idiomatic second.

## Nullability

- Treat Bukkit, Vault, WorldEdit, Cloud, Adventure, and other server/library API returns as nullable unless the API contract is explicit and locally verified.
- Do not use `!!` to hide uncertainty. Prefer early return, `?:`, local `val` snapshots, or explicit failure with a useful message when a missing value is truly impossible.
- Snapshot mutable nullable fields into local values before use instead of relying on smart casts.
- Bukkit annotates many returns `@NotNull` (`Player#getUniqueId`, `Block#getRelative`, `World#getName`, …). Kotlin then emits
  `checkNotNullExpressionValue`, so a Mockito mock that was never stubbed turns a previously harmless `null` into
  `NullPointerException: getX(...) must not be null` — a failure the Java version did not have. When the Java source guarded the
  value with an explicit `!= null`, keep that tolerance: widen the local (`val uuid: UUID? = player.uniqueId`) or route the call
  through a helper whose return type is nullable:

  ```kotlin
  /** Bukkit 标注 getRelative 为非空，这里刻意放宽成可空以保留 Java 版本的防御性判断。 */
  private fun relativeBlockOrNull(block: Block, x: Int, y: Int, z: Int): Block? = block.getRelative(x, y, z)
  ```

  Passing the value straight into a nullable parameter also avoids the check. Where the Java source dereferenced the value
  without a guard, leave it non-null — a NPE there is the original behavior.

## Java-Friendly Public API

- Keep method names and signatures that Java code calls.
- Use `@JvmOverloads` only when Java callers need default-argument overloads.
- Use `@JvmStatic` for companion/object factories that Java callers should invoke as static methods.
- Avoid exposing top-level functions, inline/reified APIs, or Kotlin-only DSLs as plugin/shared-module public API.

## Migration Style

- Prefer mechanical conversion for plugin main classes, commands, listeners, repositories, and runtime-sensitive logic.
- Do not introduce coroutines in this codebase. Existing Bukkit/Folia scheduling semantics stay in cubex-scheduler and plugin lifecycles.
- Do not casually rewrite domain models into `data class` or otherwise change `equals`, `hashCode`, constructors, getters, or serialization shape.
- Keep scope-function nesting shallow. If `apply`/`also` makes control flow harder to audit, use straightforward statements.
- Avoid callable references that can trigger Kotlin reflection types at runtime, such as `::foo` in contexts that generate `KFunction`. The convention excludes `kotlin/reflect/**`; use ordinary lambdas instead. Any real reflect need requires explicit review.
- Do not mix Kotlin migration with gameplay, config, database, command, permission, or text changes.

## Interop Traps Found During Migration

- **Static mocks stop intercepting companion helpers.** `@JvmStatic` in a *companion* object still resolves to
  `Foo.Companion.bar()` at Kotlin call sites, so an existing `mockStatic(Foo.class)` in a Java test silently stops
  intercepting once the caller becomes Kotlin. Named `object` declarations do generate a real static that stays
  interceptable. When a converted call site is covered by a static mock, either move the helper into a named object
  (Metro: `GuiItemMarker`) or point the test at the real registry object (Metro: `TrainTaskRegistry`).
- **`@JvmOverloads` bridges are for Java only.** A Kotlin caller that omits a default argument still invokes the
  full-arity method, so `verify(mock).open(a, b, 0)` must become `verify(mock).open(a, b, 0, null)`.
- **Bukkit `@NotNull` returns cannot be overridden as nullable.** `InventoryHolder#getInventory()` is annotated
  `@NotNull`, but Metro holders legitimately report `null` before their inventory exists. Keep the nullable
  declaration in a tiny Java base class (`NullableInventoryHolder`) rather than forcing `!!` or changing behaviour.
- **Raw types have no Kotlin equivalent.** `PaperCommandManager.builder(...)` names a Paper class that is absent from
  the Spigot compile classpath; Java raw types bypass it, Kotlin cannot. Isolate that single call in a documented Java
  shim (`PaperCommandManagerBootstrap`) instead of resorting to reflection.
- **Optional command arguments must be nullable.** Cloud and the reflective Bukkit fallback both pass `null` for an
  absent `[optional]` argument; a non-null Kotlin parameter turns that into an intrinsics NPE.
- **Managers that the old Java code null-checked stay nullable.** Use `lateinit` only where every caller already
  assumed non-null; keep `T?` (plus the existing guards) wherever call sites test for null, so behaviour is preserved.

## Jar Gate

For each opt-in plugin:

- `unrelocatedKotlin=0`: no `kotlin/**` entries in the jar.
- Kotlin stdlib is relocated to `org/cubexmc/<plugin>/libs/kotlin/**`.
- No `kotlin/reflect/**` or relocated reflect entries unless explicitly approved.
- `kotlin/reflect/** = 0`: no relocated or unrelocated Kotlin reflection classes in the jar unless explicitly approved.
- Kotlin class bytecode remains Java 17 (`major version: 61`).
- `plugin.yml`, commands, permissions, api-version, Metrics ID, SQLite native filtering, and bundled data/config/lang files remain unchanged unless the task explicitly says otherwise.
- Non-opt-in plugins must have unchanged jar content lists and no Kotlin runtime.
