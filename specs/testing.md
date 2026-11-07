# Photography testing

> Standard: Agentic Engineering Standards v1.2.0

## Confidence model

Tests follow responsibility boundaries. Most behavior is proved through domain and application components; adapters are proved against the real external representation or resource they translate. A smaller set of browser journeys checks that those boundaries compose into a usable editor.

| Layer | What it establishes |
| --- | --- |
| Domain and application components | Business rules, state transitions and orchestration through public operations, substituting effectful collaborators. |
| Adapter seams | Faithful translation and failure behavior against real HTTP, SQLite, files, image codecs or configuration. |
| Browser components | User-observable interaction, reactive state, accessibility and focus through rendered controls. |
| Local application journeys | Integration, visual hierarchy and native browser behavior that isolated components cannot establish. |
| Native publishing | InDesign and final-output inspection establish print fidelity; automated handoff and script checks establish representation and orchestration. |
| Architecture and build gates | Dependency direction, type safety and reproducible delivery across supported components. |

Transactional, concurrency and recovery guarantees need evidence where state is actually committed. Cross-resource photo publication also needs real storage and restart-oriented verification; mocked collaboration alone cannot establish those guarantees.

Each component suite exercises its owner and substitutes its collaborators, including child UI components. Substitutes supply independently arranged responses and record requests and effects separately; saved-state evidence comes from arranged reads at component boundaries and actual committed state at persistence seams.

Recovery confidence requires evidence for both the persisted commit and the client's knowledge of its outcome. Partial saves, lost acknowledgements and stale reads must be exercised across those boundaries to establish that recovery preserves successful work.

Reversible editing needs cross-owner journeys to establish chronological intent and history lifetime. Component tests prove compensation and retained progress; real persistence and access boundaries establish saved round trips and interference handling, including identity changes during restoration.

Native text entry, selection and spatial/focus continuity require actual-browser checks through pending and failed saves. Component assertions establish interaction logic but cannot alone prove browser-native behavior or stable presentation.

Exclusive access needs component proof at its authority: connection/grant fencing, independent lease expiry, current affected relationships and admitted mutation completion must serialize with competing acquisition.

HTTP seams prove credential translation and exact-generation stream finalization; SQLite seams prove persistence and rollback rather than recreating lock policy. Browser components establish retained drafts and fail-closed editing across ownership loss.

Actual socket cancellation and multiple browser engines establish close/reload recovery when departure requests are dropped. Native journeys isolate profiles and tabs, include the local proxy, and check authoritative refresh without replaying retained drafts.

## Nomenclature

Name domain and application suites `<Boundary>ComponentTest` and adapter suites `<Adapter>SeamIntegrationTest`; TypeScript uses the corresponding `.componentTest` and `.seamIntegrationTest` suffixes. A seam test crosses the adapter's real external boundary; substituting a network call establishes component behavior instead.

Case descriptions start with `should` and name the observable guarantee in domain vocabulary. Keep runner-required prefixes, such as Python's `test_`, before that description.

## Change validation

Project acceptance criteria into the owning suites and retain traceability with the change evidence. Exercise the complete local journey before archival, with isolated data and read-only access to original photographs.

Coverage is an enforcing guardrail, not a substitute for boundary evidence or a reason to duplicate tests. Build configuration owns thresholds and exclusions; [Contributing](../CONTRIBUTING.md) owns conventions and gate commands, and [pipeline testing](../ci/specs/testing.md) owns verification infrastructure.
