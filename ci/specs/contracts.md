# Dagger contracts

## Callable interface

[The module](../../.dagger/src/index.ts) owns signatures and defaults. The CLI exposes its functions in kebab case.

| Function | Result and obligation |
| --- | --- |
| `changed` | Committed changes become comma-separated component names in canonical order, or `none`; this does not execute checks. |
| `verify` | Checks selected components and reports which completed; `all` bypasses selection. No selected component returns `verify: nothing affected`, not proof of shared host checks. |
| `backend-check`, `frontend-check`, `pipeline-check` | Execute one component gate and return its output; nonzero tool exits fail the call. |
| `build` | Returns the runtime container with source revision and build-time metadata. |
| `release-version` | Produces a UTC timestamped version. [Version policy](../../.dagger/src/version.ts) owns generation and validation. |
| `publish` | Requires a valid release version and Dagger Secret; returns the registry publication result. |

## Consumed boundaries

| Dependency | Definition and required interpretation |
| --- | --- |
| Git checkout | [Git adapter](../../.dagger/src/hooks/git.ts): source must contain usable `.git` metadata and the requested baseline's ancestry. Direct selection falls back to the root commit if merge-base lookup fails; callers must not interpret that fallback as the intended comparison. |
| Dagger engine and SDK | [Module registration](../../dagger.json) and [locked dependencies](../../.dagger/package-lock.json): the engine executes containers and publishes their results; source snapshots exclude generated/local state according to [build environment](../../.dagger/src/buildEnv.ts). |
| Container registries | [Build environment](../../.dagger/src/buildEnv.ts) owns base-image references and target platform. Checks require network access to obtain missing images. |
| npm and JVM dependency repositories | [Frontend hook](../../.dagger/src/hooks/frontend.ts), [backend hook](../../.dagger/src/hooks/backend.ts) and [pipeline hook](../../.dagger/src/hooks/pipeline.ts) run locked dependency installation and build tools; caches reduce downloads but do not replace dependency resolution. |
| GHCR publication | [Publish operation](../../.dagger/src/index.ts) uses the supplied Secret for registry authentication; [delivery ownership](design.md#image-delivery) distinguishes publication from release completion. |

The [shared verification wrapper](../../scripts/verify) is a caller of Dagger, not part of its callable API. [Contribution validation](../../CONTRIBUTING.md#validation) explains the extra checks that caller supplies.
