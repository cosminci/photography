---
name: sdd
description: Use for product changes, investigations, change specs and living-document synchronization.
---
# Shared [SDD](https://github.com/cosminci/agentic-workflows)

1. Run `bash scripts/workflows context` from the repository root; pass `--revision` when resuming recorded work.
2. Read the returned entrypoint and localGuidance, then load the entrypoint's required phase context.
3. Record loaded paths and the resolved revision in ignored work state; reuse that revision throughout the task.
