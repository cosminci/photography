---
name: sdd
description: Use for product changes, investigations, change specs and living-document synchronization.
---
# Shared [SDD](https://github.com/cosminci/agentic-workflows)

1. From the repository root, run `bash scripts/workflows context`. If a task already has a recorded revision, pass `--revision <revision>`.
2. Read the returned `entrypoint` and `localGuidance` before acting.
3. Load the entrypoint's required context for the current phase. Record the resolved revision and loaded paths in ignored task state.

Reuse that revision throughout the task; resolve additional phase context against the same snapshot.
