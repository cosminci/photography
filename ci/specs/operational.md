# Pipeline operations

> Standard: Agentic Engineering Standards v1.2.0

## Workflows

Pull-request CI uses the shared verification entrypoint against the target branch. The manually dispatched main-branch workflow runs every component. [Workflow definitions](../../.github/workflows/) own triggers and toolchain pins.

Releases are manually authorized from main after all verification gates pass. Each immutable image version has a matching Git tag; release completion requires verified anonymous registry access.

Install the pinned toolchain from [Contributing](../../CONTRIBUTING.md) and make Docker available. Component verification requires no application credentials.

## Local push verification

Enable the hook per clone with `git config core.hooksPath .githooks` from the repository root. It uses `mise exec` when available and calls the same `scripts/verify` entrypoint as pull-request CI; Docker and the pinned toolchain are required.

For a manual shared run, fetch the target branch and supply its reference:

```sh
git fetch origin main
bash scripts/verify origin/main
```

Verify the committed checkout: the hook requires the outgoing revision to be HEAD and the tracked/non-ignored tree to be clean. Ignored build outputs and `.agent-work/` evidence remain allowed.

Feature pushes compare with the fetched remote main; updates to an existing remote main compare with its previous revision. Missing ancestry is a checkout problem to resolve before retrying verification.

Use `dagger call verify --all` when selection is uncertain or when validating the whole system. Gate commands are listed in Contributing.

Application and pipeline verification includes the packaged runtime boundary, using disposable state independently of publication and retained NAS data.

## Diagnostics

Local verification writes stage output to the terminal; hosted runs expose it in GitHub Actions logs. Dagger reports container execution and cache reuse. Identify the failing stage and preserve its output before retrying against the same candidate and baseline.

Failures in source checkout, module validation and application delivery have different owners. Repair the failing boundary rather than bypassing the hook or changing gate settings; pipeline operation does not require the application or NAS photo services to be running.
