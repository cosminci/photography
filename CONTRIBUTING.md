# Contributing

## Shared guidance

From the repository root, resolve the shared workflow:

```sh
bash scripts/workflows context
```

Read the returned entrypoint, conventions, Typelevel profile and local guidance for your task. Record the revision and reuse it with `--revision` for later resolution in the same task.

If resolution reports an unavailable shared checkout, configure the machine-local `agentic.workflowsPath` Git setting to that checkout and retry. [Agent context](.agents/README.md) explains registration; [AGENTS.md](AGENTS.md) routes product and safety context.

## Setup

Install the toolchain pinned in [.mise.toml](.mise.toml):

```sh
mise install
```

Make the installed tools available on your shell's PATH. Local application startup also requires Python 3 and sbt; component verification requires Docker.

Use the [local launcher](specs/operational.md#run-locally) to run the application. Select isolated storage before experiments; preserve original photographs and retained state under [local safety rules](.agents/skills/sdd/local.md#storage-and-deployment-safety).

## Verification

Run commands from the repository root. Choose the smallest gate covering the changed responsibility, then run the shared verification required for delivery.

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

Dagger selection compares committed revisions; it does not select work from uncommitted edits. Direct component gates do not include the shared entrypoint's Markdown and host-side module checks.

Follow [local validation requirements](.agents/skills/sdd/local.md#start-and-validate) for mandatory checks on each change and pipeline-module edits.

Enable the pre-push hook and follow [pipeline operations](ci/specs/operational.md#local-push-verification) for clean-checkout and baseline rules. [Testing](specs/testing.md) owns evidence boundaries; source configuration owns compiler, analysis and coverage gates.
