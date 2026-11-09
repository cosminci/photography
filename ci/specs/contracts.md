# Dagger contracts

## Callable interface

Call through `dagger call`; `dagger call --help` and each command's `--help` expose arguments. `--source` defaults to the current checkout; `--base` defaults to `origin/main` for change selection.

| CLI command | Result and obligation |
| --- | --- |
| `changed [--base=<ref>]` | Committed changes become comma-separated component names in canonical order, or `none`; this does not execute checks. |
| `verify [--base=<ref>] [--all]` | Checks selected components and reports which completed; `--all` bypasses selection. No selected component returns `verify: nothing affected`, not proof of shared host checks. |
| `backend-check`, `frontend-check`, `pipeline-check` | Execute one component gate and return its output; nonzero tool exits fail the call. |
| `build [--version=<version>]` | Returns the runtime container with source revision and build-time metadata; the default version is `dev`. |
| `release-version` | Produces a UTC `YYYY.M.D-THHMMSS` version with unpadded month/day and padded time. |
| `publish --tag=<version> --token=<secret> [--registry-user=<user>]` | Requires a valid calendar/time release version and Dagger Secret; returns the registry publication result. The registry user defaults to `cosminci`. |

## Consumed boundaries

| Dependency | Protocol / interface | Contract |
| --- | --- | --- |
| Git checkout | Git CLI over local checkout metadata | Source must contain usable `.git` metadata and the requested baseline's ancestry. Direct selection falls back to the root commit if merge-base lookup fails; callers must not interpret that fallback as the intended comparison. |
| Dagger engine | GraphQL API through Dagger CLI / SDK; container execution | [Module registration](../../dagger.json) pins the engine version and selects the SDK. Source snapshots retain Git metadata but exclude generated dependencies, build outputs and local/work state. |
| Container registries | OCI / Docker image pull and push over HTTPS | Checks require network access for missing base images. Runtime images target `linux/amd64`; the [runtime Dockerfile](../../containers/photography/Dockerfile) owns runtime packaging. |
| npm and JVM dependency repositories | HTTPS package/artifact resolution through npm, sbt and Coursier | npm uses lockfile-based installation; JVM tools resolve declared dependencies. Caches reduce downloads but do not replace dependency resolution. |
| GHCR publication | Authenticated OCI image push to `ghcr.io/cosminci/album-layout-studio:<version>` | The supplied Dagger Secret authenticates the registry user; [delivery ownership](design.md#image-delivery) distinguishes publication from release completion. |

The [shared verification wrapper](../../scripts/verify) is a caller of Dagger, not part of its callable API. [Contribution validation](../../CONTRIBUTING.md#validation) explains the extra checks that caller supplies.
