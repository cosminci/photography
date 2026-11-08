# Agent entrypoint

For changes, investigations, specs and documentation work, start with the [SDD router](.agents/skills/sdd/SKILL.md). Resolve and retain one workflow revision, then load its phase guidance and [local safety rules](.agents/skills/sdd/local.md).

| Task | Context to load |
| --- | --- |
| Locate product knowledge or source boundaries | [README](README.md) |
| Change product behavior | Current [design](specs/design.md) and [contracts](specs/contracts.md); maintainer-approved scope controls decisions |
| Choose validation | [Testing](specs/testing.md) and [contribution gates](CONTRIBUTING.md#verification) |
| Change browser layout, controls, navigation, shortcuts or copy | [UX patterns](frontend/UX-PATTERNS.md) |
| Change verification or publication | [Pipeline context](ci/specs/design.md), then its contracts and operations |
| Touch deployment, storage or original photographs | [Local storage safety](.agents/skills/sdd/local.md#storage-and-deployment-safety), [operations](specs/operational.md) and the [NAS runbook](unraid/README.md) |

Preserve active agents' branches and local state; use a separate worktree when another agent owns the checkout. Keep backend, frontend and pipeline decisions with their owning boundaries.
