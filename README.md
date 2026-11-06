# Photography

Album Layout Studio organizes chapter photo collections and edits persisted album compositions, with a SolidJS browser workspace and a Scala 3 backend.

- [DESIGN-PRINCIPLES.md](DESIGN-PRINCIPLES.md) — architecture and engineering principles.
- [CONTRIBUTING.md](CONTRIBUTING.md) — toolchain, workflow, conventions, and definition of done.
- [AGENTS.md](AGENTS.md) — operational guardrails for coding agents.
- [.agents/skills/sdd/SKILL.md](.agents/skills/sdd/SKILL.md) — spec-driven development workflow.
- [backend/](backend/) — Scala 3 backend and HTTP API built with sbt.
- [frontend/](frontend/) — application frontend.
- [InDesign scripts](scripts/indesign/) — open the empty Saal template, then run `place-spreads.jsx` or `place-cover.jsx` against the same exported JSON.
- [.dagger/](.dagger/) — affected-component CI checks used by GitHub Actions.
- [specs/](specs/) — living product design, contracts, testing, and operations context.
- [ci/specs/](ci/specs/) — living documentation for the CI pipeline.
- [LICENSE](LICENSE) — MIT.

**Frontend UI work only:** read [UX patterns](frontend/UX-PATTERNS.md) before changing layout, controls, navigation, keyboard interaction or user-facing copy.

## System context

- [Design](specs/design.md) — album model, editing journey, photo processing and architectural decisions.
- [Contracts](specs/contracts.md) — authoritative APIs, persistence and dependency boundaries.
- [Testing](specs/testing.md) — confidence strategy and test nomenclature.
- [Operations](specs/operational.md) — running locally/on the NAS, storage, recovery and observability.
