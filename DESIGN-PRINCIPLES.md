# Fundamental engineering rules

These eight principles are mandatory for all work in every consuming repository, independent of language or stack. SDD, profiles, local guidance, implementation choices and reviews must enforce them. They cannot grant exceptions or weaken these obligations. More specific rules may strengthen them.

Authority: Cosmin Ciobanu's engineering guidelines, V1 (20 March 2025), revised V2 (2 October 2026).

## Purpose

Keep cognitive load low and consistent, make extension and redesign straightforward, change code intentionally within its domain structure, minimize long-term maintenance and dependency risk, and support reuse and modularity. Gold plating with diminishing business or team value is not a goal.

## 1. Ports & Adapters

- Isolate core business logic from infrastructure through explicit ports for every external dependency, including databases, APIs and filesystems.
- Define ports close to their consuming business logic, in domain terms rather than external-system terms.
- Keep concrete adapters separate from the core. Bind implementations in composition.
- Exhaustively unit-test business logic with substituted ports. Integration-test adapters against their real external boundaries.

## 2. Domain Driven Design

- Explicitly model business concepts with strong types and constrained structures, including nonempty collections and positive values where required.
- Make invalid states unrepresentable. Avoid optional or nullable fields that permit invalid combinations; control flow must be exhaustive and explicit.
- Keep domain logic focused, isolated and independently testable without external dependencies.
- Use the business's ubiquitous language consistently in code, documentation and communication.

## 3. Fractal Design

- Apply cohesive design at every scale, from the application entrypoint to individual methods.
- Give each component, class and method one responsibility and one reason to change.
- Keep statements within a method at the same conceptual or business abstraction level; separate orchestration from implementation detail.
- Keep methods short and focused, typically 7-10 lines. Investigate larger units rather than disguising complexity to meet a count.
- Let readers progressively zoom from high-level flow into lower-level components, with each level remaining simple and focused.

## 4. Anti-Corruption Layers

- Translate external structures, terminology and semantics into internal domain models before business logic consumes them.
- Keep external communication representations distinct from internal domain representations. External models and their changes must not dictate the core model.
- Adapters own external interaction; translation maps external formats and concepts; validation establishes domain-valid inputs.
- These responsibilities must be explicit, but do not require separate classes for mechanical steps. A declarative codec may implement translation without an unnecessary DTO wrapper.

## 5. Indirection Layers

- All interactions between business components use explicit interfaces. Only pure, stateless, side-effect-free utilities are exempt.
- Define each interface from its caller's needs: minimal, domain-relevant, consumer-driven contracts.
- Inject business collaborators through these interfaces so tests can substitute them without exercising their implementations again.
- Interfaces expose responsibilities and dependencies while hiding implementation details. Each interface must express a deliberate, cohesive abstraction.

## 6. Strict Build Guardrails

- Require 100% test coverage as a build criterion. Difficulty testing code requires design investigation, not a weaker gate.
- Enable comprehensive static analysis with strict rules; fail builds on warnings or issues and maintain zero warnings.
- Use strict compiler settings to reject unsafe constructs, unused code/imports and accidental numeric widening where the language/toolchain supports these checks.
- Automate dependency updates to address CVEs, deprecations and end-of-life risks.
- Guardrails support modularity, quality and development speed; satisfying a metric does not excuse a poor design.

## 7. Keeping Complexity in Check

- Justify complexity by delivered value. Compare alternatives that fit the system and prefer fewer assumptions and moving parts.
- Ask whether a much smaller design can deliver most of the value. Narrowing approved scope still requires maintainer agreement.
- Before a spec, decide what to reuse, extend, build or split and why; revisit the decision when evidence changes.
- Inspect implementations beyond a screenful and suites around three times their production code. Treat testing difficulty as a design signal.
- Extract newly exposed responsibilities in the change that exposes them. Justify larger cohesive units and repair affected design defects rather than leaving broken windows.

## 8. Code Expresses Intent

- Express intent through meaningful names, strong types, clear constructs and cohesive modules.
- Before adding a comment, investigate whether the use case, API, model, responsibility boundary or package structure needs correction.
- Keep related behavior together. Share knowledge only when divergence between callers would be a bug, not merely because code looks similar.
- Keep rationale in concise, authoritative documentation. Comments are eligible only for constraints code cannot express; stricter existing comment rules remain binding.

## Enforcement

Read these principles before investigating, proposing, implementing or reviewing changes. Check all eight against the affected work and record source-backed findings; language and task type determine applicability, not whether the principles are authoritative.

Missing enforcement is a defect to expose and resolve, not permission to lower the standard. Existing passing tests, local precedent, convenience and maintainer approval of a feature do not establish compliance. Preserve all stricter existing safety, architecture, testing and comment rules.
