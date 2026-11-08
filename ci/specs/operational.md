# Pipeline operations

> Standard: Agentic Engineering Standards v1.2.0

## Workflows

| Invocation | What runs |
| --- | --- |
| Pull request | Shared verification against the target branch. |
| Manual verification from main | Host-side module validation and every component gate. |
| Manual publication from main | Host-side module validation, every component gate, image publication, public/anonymous access checks and matching Git tag. |

[Workflow definitions](../../.github/workflows/) own triggers, permissions and toolchain pins. [Pipeline design](design.md) explains responsibility boundaries and completion.

For local checks, install the pinned toolchain from [Contributing](../../CONTRIBUTING.md) and make Docker available. Component verification requires no application credentials.

## Local push verification

Enable the hook once per clone from the repository root:

```sh
git config core.hooksPath .githooks
```

The hook uses `mise exec` when available and calls the same `scripts/verify` entrypoint as pull-request CI. Verify a committed, clean checkout so the checked files match the outgoing HEAD; ignored build outputs and `.agent-work/` evidence remain allowed.

For a manual shared run, fetch the target branch and supply its reference:

```sh
git fetch origin main
bash scripts/verify origin/main
```

Feature pushes compare with the fetched remote main; updates to an existing remote main compare with its previous revision. Missing ancestry is a checkout problem to resolve before retrying verification.

Use `dagger call verify --all` when component selection is uncertain or when validating the whole system. This command runs component gates, not the shared Markdown and host-side module checks; [Contributing](../../CONTRIBUTING.md#verification) lists both.

## Publication and NAS handoff

Dispatch publication only with maintainer authorization from main. Record the published image version, digest and revision; each immutable version must have its matching Git tag.

If publication succeeds but visibility verification or tagging fails, preserve the existing image. Follow [publication recovery](../../unraid/README.md#publish) to verify anonymous access and recover the tag at the exact built revision.

Use the [Unraid runbook](../../unraid/README.md) for installation and replacement. Publishing an image does not authorize a NAS deployment.

## Diagnostics

Local verification writes stage output to the terminal; hosted runs expose it in GitHub Actions logs. Dagger reports container execution and cache reuse. Identify the failing stage and preserve its output before retrying against the same candidate and baseline.

Failures in source checkout, module validation and application delivery have different owners. Repair the failing boundary rather than bypassing the hook or changing gate settings; pipeline operation does not require the application or NAS photo services to be running.
