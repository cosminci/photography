# Dagger contracts

## Callable interface

Use `dagger call <command> --help` for arguments.

| CLI command | Result |
| --- | --- |
| `changed` | Comma-separated affected components in canonical order, or `none` |
| `verify` | Selected checks; `--all` checks every component |
| `backend-check`, `frontend-check`, `pipeline-check` | One component gate; tool errors fail the call |
| `build` | Runtime container with revision metadata |
| `release-version` | UTC `YYYY.M.D-THHMMSS` version |
| `publish` | Image reference; requires a valid release version and Dagger Secret |

[Gate scope and publication checks](operational.md#select-and-check).

## Consumed boundaries

| Dependency | Protocol / interface | Definition or constraint |
| --- | --- | --- |
| Git checkout | Git CLI | Requires `.git` and baseline ancestry; missing merge-base falls back to the root commit |
| Dagger engine | GraphQL through CLI / SDK | [Module registration](../../dagger.json) |
| Container registries | OCI / HTTPS | Runtime target: `linux/amd64`; [Dockerfile](../../containers/photography/Dockerfile) |
| Package repositories | HTTPS through npm, sbt and Coursier | [Frontend lockfile](../../frontend/package-lock.json), [pipeline lockfile](../../.dagger/package-lock.json), [JVM build](../../backend/build.sbt) |
| GHCR | OCI / HTTPS: `ghcr.io/cosminci/album-layout-studio:<version>` | Dagger Secret authentication |
