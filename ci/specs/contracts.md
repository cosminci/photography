# Pipeline contracts

> Standard: Agentic Engineering Standards v1.2.0

## Contract inventory

| Surface | Authoritative source |
| --- | --- |
| Public Dagger functions and component gates | [Dagger module](../../.dagger/src/index.ts) |
| Path-to-component ownership | [Selection policy](../../.dagger/src/selection.ts) |
| Shared verification and outgoing-revision validation | [Verification entrypoint](../../scripts/verify) and [pre-push hook](../../.githooks/pre-push) |
| Hosted invocation and toolchain pins | [GitHub workflows](../../.github/workflows/) |
| Release version and image identity | [Version rules](../../.dagger/src/version.ts), [image assembly](../../.dagger/src/hooks/image.ts) and [publication workflow](../../.github/workflows/release.yml) |
| NAS container settings | [Unraid template](../../unraid/photography.xml) |

## Completion semantics

Successful verification means every required stage completed successfully. Selecting no application component still requires the shared Markdown and pipeline-module checks; an explicit all-component run bypasses selection, not the component gates.

Dagger change selection compares committed revisions. Uncommitted edits are not evidence of an affected component; the pre-push hook requires a clean checkout so the checked candidate matches the outgoing revision.

Release completion includes anonymous registry access and the matching Git tag. [Pipeline design](design.md#release-boundary) explains partial publication; the [runbook](../../unraid/README.md#publish) owns recovery.
