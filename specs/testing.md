# Photography testing

> Standard: Agentic Engineering Standards v1.2.0

## Confidence model

Prove behavior at the boundary that owns it. Domain and application components substitute collaborators; adapter seams cross real external boundaries. Browser journeys establish integration and native behavior.

| Layer | What it establishes |
| --- | --- |
| Domain and application components | Business rules, state transitions and orchestration through public operations, substituting effectful collaborators. |
| Adapter seams | Faithful translation and failure behavior against real HTTP, SQLite, files, image codecs or configuration. |
| Browser components | User-observable interaction, reactive state, accessibility and focus through rendered controls. |
| Local application journeys | Integration, visual hierarchy and native browser behavior that isolated components cannot establish. |
| Native publishing | InDesign and final-output inspection establish print fidelity; automated handoff and script checks establish representation and orchestration. |
| Architecture and build gates | Dependency direction, type safety and reproducible delivery across supported components. |

Each component suite exercises its owner and substitutes collaborators, including child UI components. Substitutes supply independently arranged responses and record requests and effects separately.

## Risk and evidence

| Guarantee or risk | Required evidence |
| --- | --- |
| Atomic persistence, concurrency and rollback | Real persistence seams establish committed state; component assertions alone cannot prove a transaction. |
| Photo publication across SQLite and thumbnail storage | Real storage and restart-oriented checks establish publication, cleanup and recovery. |
| Partial saves, lost acknowledgements and stale reads | Exercise both the persisted commit and the client's knowledge of its outcome; recovery preserves successful work. |
| Undo/Redo across content owners | Cross-owner journeys establish chronology and history lifetime; components prove compensation and retained progress; persistence/access seams prove round trips, interference and regenerated identities. |
| Native text entry, selection and spatial/focus continuity | Actual-browser checks cover pending and failed saves; rendered component logic alone cannot establish native behavior or stable presentation. |
| Exclusive editing access | Authority components prove connection/grant fencing, independent lease expiry, current affected relationships and serialization of admitted writes with acquisition. |
| Access translation and persistence | HTTP seams prove credential translation and exact-generation stream finalization; SQLite seams prove persistence and rollback rather than repeat lock policy. |
| Connection or ownership loss | Browser components prove retained drafts and fail-closed editing. |
| Close/reload with dropped departure requests | Actual socket cancellation and multiple browser engines; isolate profiles/tabs, include the local proxy and check authoritative refresh without draft replay. |

## Nomenclature

Name domain and application suites `<Boundary>ComponentTest` and adapter suites `<Adapter>SeamIntegrationTest`; TypeScript uses the corresponding `.componentTest` and `.seamIntegrationTest` suffixes. A seam test crosses the adapter's real external boundary; substituting a network call establishes component behavior instead.

Case descriptions start with `should` and name the observable guarantee in domain vocabulary. Keep runner-required prefixes, such as Python's `test_`, before that description.

## Change validation

Map acceptance criteria to the owning suites and retain traceability in change evidence. Exercise the complete local journey before archival, with isolated data and read-only access to original photographs.

Coverage is an enforcing guardrail, not a substitute for boundary evidence or a reason to duplicate tests. Build configuration owns thresholds and exclusions; [Contributing](../CONTRIBUTING.md) owns conventions and gate commands, and [pipeline testing](../ci/specs/testing.md) owns verification infrastructure.
