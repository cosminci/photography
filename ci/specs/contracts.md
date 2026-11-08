# Pipeline contracts

## Provided

| Boundary | Definition |
| --- | --- |
| Gates and selection | [Module](../../.dagger/src/index.ts), [selection policy](../../.dagger/src/selection.ts) |
| Push verification | [Entrypoint](../../scripts/verify), [hook](../../.githooks/pre-push) |
| Release identity | [Version rules](../../.dagger/src/version.ts), [image assembly](../../.dagger/src/hooks/image.ts) |

## Consumed

| Dependency | Required contract |
| --- | --- |
| Git | Available candidate/baseline ancestry: [Git boundary](../../.dagger/src/hooks/git.ts). |
| GitHub Actions/Packages | Dispatch, permissions and package visibility: [workflows](../../.github/workflows/). |
| GHCR | OCI publication and manifest reads; release completion requires public anonymous access and a matching Git tag: [release workflow](../../.github/workflows/release.yml). |
| Dagger/Docker | Container execution and image delivery: [build environment](../../.dagger/src/buildEnv.ts), [Dockerfile](../../containers/photography/Dockerfile). |
| Unraid/WUD | Template application, release discovery and host-triggered replacement: [template](../../unraid/photography.xml). |
