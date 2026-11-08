# Dagger operations

Run from the repository root with the [contributor toolchain](../../CONTRIBUTING.md#setup) installed and Docker available. Checks use isolated container state; the service and NAS need not be running.

## Select and check

Commit the candidate and fetch the comparison branch before selective verification. Selection compares committed HEAD with the merge-base, not local edits.

```sh
git fetch origin main
dagger call changed --base=origin/main
dagger call verify --base=origin/main
```

Use `dagger call verify --all` to check all components without Git selection. For a single gate, use `dagger call backend-check`, `dagger call frontend-check` or `dagger call pipeline-check`; [design](design.md#selection-and-execution) explains their different scope.

These commands do not replace the shared host checks required for publication; use [contribution validation](../../CONTRIBUTING.md#validation) for that entrypoint.

## Build without publishing

```sh
dagger call build --version=dev export --path=.agent-work/photography-dev.tar
```

The result is a Docker-loadable image archive. [Image assembly](../../.dagger/src/hooks/image.ts) and the [runtime Dockerfile](../../containers/photography/Dockerfile) own its packaging; [delivery ownership](design.md#image-delivery) explains the prerequisites.

## Diagnose failures

Keep the failing stage's output and candidate/baseline revisions. Dagger logs distinguish tool failures from engine/container setup failures; fix that boundary without bypassing the gate.

For unexpected selection, inspect `git merge-base origin/main HEAD` and `git diff --name-only origin/main...HEAD` before blaming the mapping. Missing ancestry can trigger the direct Git adapter's fallback; fetch the intended baseline and rerun.

If container Git commands cannot resolve a linked worktree's external metadata, use a full clone containing the same committed candidate and baseline. Keep the original worktree intact; changing the source container does not authorize skipping verification.
