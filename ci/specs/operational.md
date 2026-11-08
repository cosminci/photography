# Pipeline operations

## Workflows

Use the [workflow definitions](../../.github/workflows/) to select a hosted run or inspect its dispatch settings. [Pipeline design](design.md) owns stage responsibilities; [contracts](contracts.md) owns provided and consumed boundaries.

For local checks, follow [Contributing](../../CONTRIBUTING.md#setup). Component verification requires no application credentials.

## Local push verification

Enable the hook once per clone from the repository root:

```sh
git config core.hooksPath .githooks
```

The hook uses `mise exec` when available. Verify a committed, clean checkout so the checked files match the outgoing HEAD; ignored build outputs and `.agent-work/` evidence remain allowed.

For a manual shared run, fetch the target branch and supply its reference:

```sh
git fetch origin main
bash scripts/verify origin/main
```

Feature pushes compare with the fetched remote main; updates to an existing remote main compare with its previous revision. Missing ancestry is a checkout problem to resolve before retrying verification.

For direct component checks, use the [contribution commands](../../CONTRIBUTING.md#verification). Direct Dagger verification omits shared Markdown and host-side module checks, and selection ignores uncommitted edits.

## Publication and NAS handoff

Follow the [Unraid runbook](../../unraid/README.md#publish) for publication, partial-release recovery and NAS handoff. It owns operator actions and their authorizations.

## Diagnostics

Local verification writes stage output to the terminal; hosted runs expose it in GitHub Actions logs. Dagger reports container execution and cache reuse. Identify the failing stage and preserve its output before retrying against the same candidate and baseline.

Failures in source checkout, module validation and application delivery have different owners. Repair the failing boundary rather than bypassing the hook or changing gate settings; pipeline operation does not require the application or NAS photo services to be running.
