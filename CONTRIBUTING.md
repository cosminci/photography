# Contributing

## Shared guidance

Follow the [SDD router](.agents/skills/sdd/SKILL.md) to resolve shared guidance. Load its conventions and Typelevel profile for implementation and review; [AGENTS.md](AGENTS.md) routes product and safety context.

If resolution fails, check the registration described in [agent context](.agents/README.md).

## Setup

Install the toolchain pinned in [.mise.toml](.mise.toml):

```sh
mise install
```

Make the installed tools available on your shell's PATH. Local application startup also requires Python 3 and sbt; component verification requires Docker.

Use the [local launcher](specs/operational.md#run-locally) to run the application, following [local safety rules](.agents/skills/sdd/local.md#storage-and-deployment-safety).

## Verification

Choose the gate for the changed responsibility under [local validation requirements](.agents/skills/sdd/local.md#start-and-validate).

| Check | Command |
| --- | --- |
| Backend | `dagger call backend-check` |
| Frontend and InDesign scripts | `dagger call frontend-check` |
| Pipeline component | `dagger call pipeline-check` |
| Affected components | `dagger call verify --base=<git-ref>` |
| All components when impact is uncertain | `dagger call verify --all` |
| Host-side pipeline-module validation | `npm ci --prefix .dagger && dagger develop && npm --prefix .dagger run verify` |
| Changed Markdown before commit | `python3 .agents/skills/sdd/scripts/check_markdown_wrapping.py <files>` |
| Shared pre-push/CI verification of committed HEAD | `bash scripts/verify <base>` |

Enable the pre-push hook and follow [pipeline operations](ci/specs/operational.md#local-push-verification) for committed-revision selection, shared checks and clean-checkout rules. [Testing](specs/testing.md) owns evidence boundaries; source configuration owns compiler, analysis and coverage gates.
