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

Content recovery needs both browser evidence that acknowledgements prevent write replay and persistence evidence that reopened state retains the actual commit. Chapter metadata/gear partial saves and reads racing with saved publication must preserve the already-resolved portion.

Native caption text entry and selection, responsive book presentation and layout stability during pending saves require actual-browser checks. Component assertions alone do not establish native editing behavior or the absence of transient reflow and focus-induced scrolling.

## Nomenclature

Name domain and application suites `<Boundary>ComponentTest` and adapter suites `<Adapter>SeamIntegrationTest`; TypeScript uses the corresponding `.componentTest` and `.seamIntegrationTest` suffixes. A seam test crosses the adapter's real external boundary; substituting a network call establishes component behavior instead.

Case descriptions start with `should` and name the observable guarantee in domain vocabulary. Keep runner-required prefixes, such as Python's `test_`, before that description.

## Change validation

Project acceptance criteria into the owning suites and retain traceability with the change evidence. Exercise the complete local journey before archival, with isolated data and read-only access to original photographs.

Coverage is an enforcing guardrail, not a substitute for boundary evidence or a reason to duplicate tests. Build configuration owns thresholds and exclusions; [Contributing](../CONTRIBUTING.md) owns conventions and gate commands, and [pipeline testing](../ci/specs/testing.md) owns verification infrastructure.
