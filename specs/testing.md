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
| Architecture and build gates | Dependency direction, type safety and reproducible delivery across supported components. |

Transactional, concurrency and recovery guarantees need evidence where state is actually committed. Cross-resource photo publication also needs real storage and restart-oriented verification; mocked collaboration alone cannot establish those guarantees.

Recovery confidence requires evidence for both the persisted commit and the client's knowledge of its outcome. Partial saves, lost acknowledgements and stale reads must be exercised across those boundaries to establish that recovery preserves successful work.

Native text entry, selection and spatial/focus continuity require actual-browser checks through pending and failed saves. Component assertions establish interaction logic but cannot alone prove browser-native behavior or stable presentation.

## Nomenclature

Name domain and application suites `<Boundary>ComponentTest` and adapter suites `<Adapter>SeamIntegrationTest`; TypeScript uses the corresponding `.componentTest` and `.seamIntegrationTest` suffixes. A seam test crosses the adapter's real external boundary; substituting a network call establishes component behavior instead.

Case descriptions start with `should` and name the observable guarantee in domain vocabulary. Keep runner-required prefixes, such as Python's `test_`, before that description.

## Change validation

Project acceptance criteria into the owning suites and retain traceability with the change evidence. Exercise the complete local journey before archival, with isolated data and read-only access to original photographs.

Coverage is an enforcing guardrail, not a substitute for boundary evidence or a reason to duplicate tests. Build configuration owns thresholds and exclusions; [Contributing](../CONTRIBUTING.md) owns conventions and gate commands, and [pipeline testing](../ci/specs/testing.md) owns verification infrastructure.
