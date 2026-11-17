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

## Engine storage

Before the first local Dagger command, run `python3 scripts/configure_dagger_engine.py`; the verification wrapper and publish workflow run it automatically. The installer writes the [retention policy](../../scripts/dagger-engine.json) under `$XDG_CONFIG_HOME/dagger/engine.json` when set, otherwise under `~/Library/Application Support/dagger/engine.json` on macOS or `~/.config/dagger/engine.json` on Linux; this is a shared user configuration, and unrelated settings are preserved.

The policy makes entries unused for 72 hours eligible for removal and gives each engine a 20 GB GC budget, a 2 GB reserve, a 5 GB sweep target and a 50 GB free-space target on the engine's filesystem. These are garbage-collection thresholds, not disk quotas or a guarantee of host free space: active references can exceed the budget, age cleanup needs a GC pass, and stopped engines cannot collect their caches.

Dagger mounts this file when it creates a default engine and reads it at engine startup. An existing engine without the mount must be recreated after confirming it is idle; a mounted engine needs a restart after a policy change. For a manually created engine, mount the same file read-only at `/etc/dagger/engine.json`, keep its name explicit in the runner override, and remove that exact disposable container and its own cache volume when the experiment ends.

## Build without publishing

```sh
dagger call build --version=dev export --path=.agent-work/photography-dev.tar
```

The result is a Docker-loadable image archive. The [runtime Dockerfile](../../containers/photography/Dockerfile) owns its packaging; [delivery ownership](design.md#image-delivery) explains the prerequisites.

## Diagnose failures

Keep the failing stage's output and candidate/baseline revisions. Dagger logs distinguish tool failures from engine/container setup failures; fix that boundary without bypassing the gate.

For unexpected selection, inspect `git merge-base origin/main HEAD` and `git diff --name-only origin/main...HEAD` before blaming the mapping. Missing ancestry can trigger the direct Git adapter's fallback; fetch the intended baseline and rerun.

If container Git commands cannot resolve a linked worktree's external metadata, use a full clone containing the same committed candidate and baseline. Keep the original worktree intact; changing the source container does not authorize skipping verification.
