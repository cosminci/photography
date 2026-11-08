# Photography

Album Layout Studio turns chapter photo collections into saved books for browser presentation and InDesign print composition. A SolidJS workspace edits the book; one Scala 3 backend owns saved state and derived previews. Original photographs remain independently owned.

## Start here

| Task | Read |
| --- | --- |
| Run locally | [Operations](specs/operational.md#run-locally) |
| Understand the book and editing lifecycle | [Design](specs/design.md) |
| Find an API, schema or dependency boundary | [Contracts](specs/contracts.md) |
| Choose test boundaries | [Testing](specs/testing.md) |
| Install, update or recover on the NAS | [Unraid runbook](unraid/README.md); [deployment topology](specs/operational.md#run-on-the-nas) |
| Understand verification and releases | [Pipeline design](ci/specs/design.md); [pipeline operations](ci/specs/operational.md) |
| Contribute | [Contributing](CONTRIBUTING.md); agents start at [AGENTS.md](AGENTS.md) |
| Change browser presentation or interaction | [UX patterns](frontend/UX-PATTERNS.md) |
| Compose print output | [InDesign scripts](scripts/indesign/): open the empty Saal template, then run `place-spreads.jsx` or `place-cover.jsx` against the same exported JSON |

Current `specs/` describe the supported product; `ci/specs/` describe the pipeline. Schemas, configuration and source own exact definitions. Archived change proposals retain history.

## Source boundaries

| Location | Responsibility |
| --- | --- |
| [backend/](backend/) | Domain, application operations, HTTP API and infrastructure adapters; built with sbt. |
| [frontend/](frontend/) | Browser workspace, Reader and client-side editing coordination. |
| [.dagger/](.dagger/) | Verification selection, component gates and image delivery. |
| [unraid/](unraid/) | Operator procedures and the container template. |

[Design principles](DESIGN-PRINCIPLES.md) points to shared engineering rules. The project is [MIT licensed](LICENSE).
