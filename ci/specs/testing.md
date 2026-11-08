# Pipeline testing

> Standard: Agentic Engineering Standards v1.2.0

## Confidence model

Pipeline confidence has two independent parts: selecting the correct work and proving that work builds. Pure policy tests establish selection; execution against real tools establishes delivery.

| Boundary | Strategy |
| --- | --- |
| Selection policy | Component tests prove changed-path interpretation and ownership independently of containers. |
| Git and shell orchestration | Process-seam tests exercise real temporary repositories and scripts, substituting expensive build commands. |
| Component delivery | Pinned containers run the actual backend, frontend and pipeline gates. |
| Release identity | Component tests prove timestamp generation and validation; the release workflow checks registry access and duplicate versions before completing publication. |
| NAS integration | An authorized smoke deployment proves the image, Unraid configuration, storage permissions and application work together, using disposable state and read-only originals. |
| Hosted integration | Pull-request CI and explicit all-component verification establish that the same entrypoints work outside local development. |

The host-side module checks validate the verification code itself. They run even when an application rebuild is unnecessary.

## Nomenclature and scope

Use the [application testing nomenclature](../../specs/testing.md#nomenclature) for component and seam suites. The boundary actually crossed determines the category, not whether the subject happens to launch another tool.

Keep scenario inventories and execution results with their tests and change evidence. Passing selection tests does not establish a successful application build; conversely, an all-component build cannot prove that selective verification is sound.
