# Photography

Album Layout Studio turns chapter-organized photographs into a saved book: compose it in the browser, read it without editing controls and export it for InDesign print composition.

The [SolidJS frontend](frontend/) and [Scala backend](backend/) ship together as one application image. [Dagger](.dagger/) checks and packages them.

## Engineering context

| Read when | Document |
| --- | --- |
| Understanding book ownership and editing/processing lifecycles | [Service design](specs/design.md) |
| Integrating API, print or infrastructure boundaries | [Service contracts](specs/contracts.md) |
| Choosing test boundaries and suite ownership | [Service testing](specs/testing.md) |
| Starting, diagnosing or recovering the service | [Service operations](specs/operational.md) |
| Publishing or installing on Unraid | [NAS runbook](unraid/README.md) |
| Changing or running Dagger | [Dagger context](ci/specs/design.md), [contracts](ci/specs/contracts.md), [testing](ci/specs/testing.md), [operations](ci/specs/operational.md) |
| Contributing | [Setup and validation](CONTRIBUTING.md), [engineering principles](DESIGN-PRINCIPLES.md) |
| Working as a coding agent | [Agent entrypoints](AGENTS.md) |

[License: MIT](LICENSE).
