# Pipeline testing

## Confidence model

| Boundary | Strategy |
| --- | --- |
| Selection policy | Component tests prove changed-path interpretation and ownership independently of containers. |
| Module validity | Host-side compiler, analysis and formatting checks exercise the verification module itself. |
| Git and shell orchestration | Process-seam tests exercise real temporary repositories and scripts, substituting expensive build commands. |
| Component delivery | Pinned containers run the actual backend, frontend and pipeline gates. |
| Release identity and access | Component tests exercise version generation/validation; workflow checks exercise the [registry contracts](contracts.md#consumed-contracts). |
| NAS integration | The [smoke procedure](../../unraid/README.md#isolated-smoke-deployment) establishes image/template/storage integration. |
| Hosted integration | Pull-request CI and explicit all-component verification establish that the same entrypoints work outside local development. |

## Nomenclature and scope

Use the [application testing nomenclature](../../specs/testing.md#nomenclature) for component and seam suites. The boundary actually crossed determines the category, not whether the subject happens to launch another tool.

Selection and delivery need independent evidence: an all-component build bypasses the selection policy.
