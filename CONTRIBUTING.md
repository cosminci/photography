# Contributing

Resolve the [SDD workflow](.agents/skills/sdd/SKILL.md); use its conventions and Typelevel profile. The [design principles](DESIGN-PRINCIPLES.md) govern all changes.

## Setup

Run `mise install` and put the pinned tools on PATH. Local startup also requires Python 3 and sbt; verification requires Docker.

## Validation

Every change runs Dagger verification. Choose the owning component; use all components when shared impact is uncertain.

| Scope | Command |
| --- | --- |
| Backend | `dagger call backend-check` |
| Frontend and InDesign scripts | `dagger call frontend-check` |
| Pipeline | `dagger call pipeline-check` |
| Changed components | `dagger call verify --base=<git-ref>` |
| All components | `dagger call verify --all` |
| Pipeline-module changes, additionally | `npm ci --prefix .dagger && dagger develop && npm --prefix .dagger run verify` |
| Markdown | `python3 .agents/skills/sdd/scripts/check_markdown_wrapping.py <files>` |

Follow [push verification](ci/specs/operational.md) before publication. [Testing](specs/testing.md) determines evidence boundaries.

## Schema changes

Before first deployment, evolve V1 schema/V2 data with migrated repository seam tests. After deployment, preserve applied migrations and add versioned changes with upgrade evidence.
