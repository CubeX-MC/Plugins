---
name: code-optimization
description: Use when Codex needs to improve code quality, maintainability, reliability, performance, testability, or architecture in a repository. Trigger for refactors, cleanup, complexity reduction, behavior-preserving optimization, reliability hardening, bug-risk reduction, or requests to make code easier for future agents to work on. Keep project-specific facts out of this skill; read the repository agent profile for local architecture, runtime, and verification rules.
---

# Code Optimization

Use this workflow for behavior-preserving or low-behavior-change code
improvements.

## Workflow

1. Read `AGENTS.md`, `docs/agent-pipeline.md`,
   `docs/agent-project-profile.md`, and `docs/agent-verification-matrix.md`
   when present.
2. Classify the optimization:
   - Structural refactor
   - Performance
   - Reliability hardening
   - Testability
   - Dead-code or duplication cleanup
3. Identify invariants before editing:
   - Public behavior
   - Persistence shape
   - API contracts
   - Threading/runtime boundaries
   - User-visible text or permissions
4. Locate current tests for the owned behavior.
5. Prefer adding or tightening tests before risky implementation changes.
6. Make the smallest useful change.
7. Run verification scaled to the risk class.
8. Record evidence and residual risk.

## Optimization Rules

- Preserve external behavior unless the user explicitly asks otherwise.
- Favor existing project boundaries and helper APIs.
- Remove duplication only when the shared abstraction is clearer than the
  duplicated code.
- Avoid broad formatting churn.
- Avoid mixing refactors with feature work unless necessary for safety.
- For runtime-sensitive systems, optimize correctness and observability before
  raw speed.

## Review Checklist

- Is the behavior still covered by tests?
- Did the change reduce complexity at the call site, not merely move it?
- Are failure paths at least as explicit as before?
- Did any public contract, config, data shape, or message change?
- Is the final evidence enough for another agent to continue safely?
