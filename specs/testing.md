# Photography testing

| Boundary | Evidence |
| --- | --- |
| Domain/application components | Rules and orchestration with substituted collaborators, including child UI components. |
| Adapter seams | Real HTTP, SQLite, filesystem, codec or configuration behavior. |
| Browser components | Rendered interaction, accessibility, focus and retained drafts. |
| Actual browser journeys | Native input, layout continuity, cross-owner history and close/reload recovery across engines and isolated profiles/tabs. |
| Print output | Automated schema/script checks plus InDesign inspection for fidelity. |

Prove transactions, rollback, concurrency and photo-write recovery where state commits. Exercise persisted results and client knowledge separately for partial saves, lost acknowledgements and stale reads.

Authority components prove lease expiry, fencing and admitted-write serialization. HTTP seams prove credential translation and exact-connection finalization; actual sockets and the local proxy establish dropped-departure recovery.

Substitutes supply arranged responses and record effects separately. Complete local journeys before archival; retain acceptance mappings and results with change evidence.

## Suite names

Use `<Boundary>ComponentTest` and `<Adapter>SeamIntegrationTest`; TypeScript uses `.componentTest` and `.seamIntegrationTest`. Cases start with `should`, after runner-required prefixes such as `test_`.

[Principles](../DESIGN-PRINCIPLES.md) govern coverage; configuration enforces it. [Contributing](../CONTRIBUTING.md) owns commands.
