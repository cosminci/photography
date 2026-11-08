# Photography testing

## Test boundaries

| Type | What to exercise |
| --- | --- |
| Component | Domain rules and usecase decisions through public operations, with collaborating ports substituted. Browser suites render their owner, substitute collaborators and use accessible controls and real events. |
| Seam integration | An adapter's real external exchange: HTTP requests with the usecase substituted, migrated SQLite, actual files, image codecs or configuration parsing. |
| Architecture | Enforced package/dependency direction, separate from behavioral scenarios. |
| Browser journey | Native text selection, scrolling/focus, tab/profile isolation and proxy/socket behavior that a simulated DOM cannot establish. |
| Print inspection | Run the exported book through InDesign and inspect the composed output; schema/script tests do not prove print fidelity. |

## Service risks

For ownership changes, test separate session and target expiry, stale connection generations and a write racing acquisition. Component tests own the admission decision; HTTP seams prove credential translation and stream finalization, while SQLite seams prove commit/rollback.

For source reconciliation, use actual original/thumbnail files and migrated SQLite to prove interrupted publication and restart cleanup. A mocked storage response cannot prove that database references and thumbnail files remain consistent.

For saves and Undo/Redo, distinguish rejected writes, acknowledged writes followed by failed reads and uncertain write outcomes. Prove partial-save recovery preserves completed work, stale reads cannot replace acknowledged state and compensation follows regenerated identities without undoing unrelated edits.

For browser recovery, isolate profiles and tabs, drop departure requests and close real event connections. Check that ownership loss retains drafts and blocks writes, reacquisition reloads saved state, and neither reconnect nor Reader replays drafts automatically.

For export, exercise missing resources and invalid saved composition as well as a valid book. Native inspection remains necessary for page geometry, captions and Cover/spine placement.

## Nomenclature

Name domain and application suites `<Boundary>ComponentTest` and adapter suites `<Adapter>SeamIntegrationTest`; TypeScript uses the corresponding `.componentTest` and `.seamIntegrationTest` suffixes. A seam test crosses the adapter's real external boundary; substituting a network call establishes component behavior instead.

Every usecase or port trait must have exactly one owning test suite. Group its operation scenarios in that suite; do not split ownership by feature, helper or result type.

Keep architecture rules in one `ArchitectureTest` suite with one test.

Case descriptions start with `should` and name the observable guarantee in domain vocabulary. Keep runner-required prefixes, such as Python's `test_`, before that description.

## Change validation

Map changed guarantees to their owning scenarios and retain results with change evidence. Use isolated application state and read-only originals for browser or print checks.

The [backend build](../backend/build.sbt) and [frontend test configuration](../frontend/vitest.config.ts) own coverage thresholds/exclusions. [Contributing](../CONTRIBUTING.md#validation) owns gate commands; [Dagger testing](../ci/specs/testing.md) explains verification infrastructure evidence.
