# Contract Agent Evidence And Handoff Template

Use this template for R3/R4 work, pull-request descriptions, or handoff between
Claude Code and Codex.

```markdown
## Task

- Request:
- Active artifact:
- Artifact phase/slice:
- Risk class:
- User-visible change: yes/no

## Starting State

- Branch:
- Contract working-tree state:
- Relevant existing changes preserved:

## Change Summary

-

## Files Touched

- `path`: why it changed

## Invariants Checked

- Escrow/item conservation:
- State-transition ownership:
- Backward-compatible data load:
- Permission/ownership boundary:
- Scheduler/restart idempotence:

## Automated Verification

- `command`: result, test count/artifact path, relevant warnings

## Manual Runtime Verification

- Paper version:
- Vault/economy provider:
- Covered:
- Not covered:
- Reason:

## Documentation Sync

- Profile:
- Active artifact status:
- README/config/plugin.yml/locales/CHANGELOG:
- Not needed because:

## Migration And Rollback

- New data/config shape:
- Legacy read path:
- Downgrade/rollback expectation:

## Residual Risk

-

## Next Executable Slice

-
```

For R0-R2 work, a compact handoff is acceptable, but it must still name the
artifact phase, commands run, remaining manual gap, and next slice.
