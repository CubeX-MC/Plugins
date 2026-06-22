---
name: hci-design
description: Use when Codex needs to design, evaluate, or improve human-facing workflows such as commands, GUIs, forms, messages, permissions, onboarding, error states, confirmations, accessibility, operator control, player/admin experience, or workflow clarity. Trigger for UX, HCI, usability, command design, GUI design, copy, localization, permission feedback, destructive-action confirmation, or confusing user flows. Keep project-specific interaction surfaces in the repository project profile, not in this skill.
---

# HCI Design

Use this workflow for human-facing behavior, especially command and GUI flows.

## Workflow

1. Read `AGENTS.md`, `docs/agent-pipeline.md`,
   `docs/agent-project-profile.md`, and relevant product docs when present.
2. Identify the actor:
   - New user
   - Routine operator
   - Admin
   - External integrator
   - Player or end user
3. Map the task:
   - Entry point
   - Required context
   - Happy path
   - Failure states
   - Recovery path
   - Confirmation or undo need
4. Check permission, ownership, and safety boundaries before optimizing flow.
5. Design for clarity under stress:
   - Explicit object IDs when names can collide.
   - Clear failure reasons.
   - No misleading prompts.
   - Confirm destructive actions.
   - Keep common paths short.
6. Update tests and docs for changed human-facing behavior.

## Evaluation Heuristics

- Can the user predict what action will happen before committing?
- Does the UI explain why an action is blocked?
- Are destructive or irreversible operations confirmed?
- Are similar objects distinguishable?
- Is feedback consistent across command, GUI, and message surfaces?
- Does the flow avoid forcing expert knowledge into routine tasks?
- Does localization or copy structure keep messages maintainable?

## Evidence Checklist

- Actor and workflow changed.
- Before/after behavior.
- Safety or confirmation behavior.
- Permission and failure feedback.
- Tests or manual checks.
- Documentation or localization updates.
