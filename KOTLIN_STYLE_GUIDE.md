# CubeX Kotlin Style Guide

This guide is intentionally small. Kotlin migration is behavior-preserving first, idiomatic second.

## Nullability

- Treat Bukkit, Vault, WorldEdit, Cloud, Adventure, and other server/library API returns as nullable unless the API contract is explicit and locally verified.
- Do not use `!!` to hide uncertainty. Prefer early return, `?:`, local `val` snapshots, or explicit failure with a useful message when a missing value is truly impossible.
- Snapshot mutable nullable fields into local values before use instead of relying on smart casts.

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

## Jar Gate

For each opt-in plugin:

- `unrelocatedKotlin=0`: no `kotlin/**` entries in the jar.
- Kotlin stdlib is relocated to `org/cubexmc/<plugin>/libs/kotlin/**`.
- No `kotlin/reflect/**` or relocated reflect entries unless explicitly approved.
- `kotlin/reflect/** = 0`: no relocated or unrelocated Kotlin reflection classes in the jar unless explicitly approved.
- Kotlin class bytecode remains Java 17 (`major version: 61`).
- `plugin.yml`, commands, permissions, api-version, Metrics ID, SQLite native filtering, and bundled data/config/lang files remain unchanged unless the task explicitly says otherwise.
- Non-opt-in plugins must have unchanged jar content lists and no Kotlin runtime.
