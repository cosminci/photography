# Pipeline contracts

## Provided contracts

| Surface | Authoritative source |
| --- | --- |
| Public Dagger functions and component gates | [Dagger module](../../.dagger/src/index.ts) |
| Path-to-component ownership | [Selection policy](../../.dagger/src/selection.ts) |
| Shared verification and outgoing-revision validation | [Verification entrypoint](../../scripts/verify) and [pre-push hook](../../.githooks/pre-push) |
| Release version and image identity | [Version rules](../../.dagger/src/version.ts), [image assembly](../../.dagger/src/hooks/image.ts) and [publication workflow](../../.github/workflows/release.yml) |

## Consumed contracts

| Dependency | Required contract | Authoritative definitions |
| --- | --- | --- |
| Git | Candidate and baseline revisions with available ancestry. | [Git boundary](../../.dagger/src/hooks/git.ts), [pre-push hook](../../.githooks/pre-push) |
| GitHub Actions and Packages API | Workflow dispatch, job permissions and package visibility lookup. | [Hosted workflows](../../.github/workflows/), [Packages API](https://docs.github.com/en/rest/packages/packages) |
| GHCR | OCI image publication and manifest reads; completed releases require public visibility and verified anonymous access. | [Publication workflow](../../.github/workflows/release.yml), [GHCR documentation](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry) |
| Dagger and Docker | Container execution, image assembly and registry delivery. | [Dagger module](../../.dagger/src/index.ts), [runtime Dockerfile](../../containers/photography/Dockerfile), [build environment](../../.dagger/src/buildEnv.ts) |
| Unraid and WUD | Container template application, release discovery and host-triggered replacement. | [Unraid template](../../unraid/photography.xml), [operator runbook](../../unraid/README.md) |

## Completion

Verification succeeds when every required stage completes. [Pipeline design](design.md#verification-boundary) owns stage responsibilities; [operations](operational.md#local-push-verification) owns committed-checkout and baseline requirements.

A release completes only when its matching Git tag is recorded after registry access verification. [Pipeline design](design.md#release-boundary) owns partial-publication semantics; the [runbook](../../unraid/README.md#recover-a-partial-release) owns recovery actions.
