# Pipeline operations

## Verify a push

Enable the hook once: `git config core.hooksPath .githooks`.

Commit changes before verification; the hook requires clean tracked/non-ignored files and outgoing HEAD. It uses `mise exec` when available.

```sh
git fetch origin main
bash scripts/verify origin/main
```

Feature pushes compare with remote main; existing main updates compare with its previous revision. Resolve missing ancestry before retrying.

Direct Dagger commands omit shared Markdown/host-module checks and ignore uncommitted edits. [Contributing](../../CONTRIBUTING.md#validation) owns gate commands.

## Diagnose failures

Preserve the failed stage's terminal or Actions output. Retry against the same candidate/baseline after repairing that boundary; keep gates enabled.

Use [workflow definitions](../../.github/workflows/) for hosted dispatch and [publication procedure](../../unraid/README.md#publish) for releases.
