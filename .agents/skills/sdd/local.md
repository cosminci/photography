# Photography ownership and safety

## Start and validate

Run application commands from the repository root. [Contributing](../../../CONTRIBUTING.md) owns setup and gate commands; [operations](../../../specs/operational.md#run-locally) owns the local launcher.

Run Dagger verification for every change, using the owning component gates and all components when shared impact is uncertain.

Pipeline-module changes also require locked dependency installation, Dagger module development and host-side module validation. Enable the pre-push hook and follow [pipeline operations](../../../ci/specs/operational.md#local-push-verification) for shared verification and revision requirements.

## Storage and deployment safety

Use isolated SQLite, original-photo and thumbnail paths from [application configuration](../../../backend/src/main/resources/application.conf). Originals are read-only; application state and thumbnails are writable.

Preserve originals and retained spike/NAS state. Replacement requires explicit owner authorization; identify disposable databases before recreation. Keep secrets in local environment/configuration and stop only processes owned by the current work.

| Lifecycle | Obligation |
| --- | --- |
| Before the first NAS deployment | Evolve V1 schema/V2 data with ordinary migrated repository seam tests; retained spike data requires authorization. |
| After deployment | Preserve applied migrations and introduce versioned changes with upgrade evidence. |
| Running and recovering | Follow the supported topology and recovery procedure in the linked operations/runbook. |

Read [operations](../../../specs/operational.md) before startup or recovery changes, and the [NAS runbook](../../../unraid/README.md) before publication, installation, updates or seeding.

## Source precedents

For Scala result sequencing, use the existing `photography.syntax.EitherSyntax.*` postfix `.eitherT` where applicable.

For UI changes, inspect [application styles](../../../frontend/src/app/app.css), workspace styles, the ChapterOutline toolbar, AlbumDialog and SpreadConfirmation. Source owns exact layout, copy and shortcuts.

## Documentation

Use the resolved writing skill with STE as a language basis, not blanket STE compliance. Preserve established terms from current specs and authoritative contracts; avoid invented synonyms.

| Document | Writing and ownership |
| --- | --- |
| Procedures and runbooks | Direct instructions, active voice, explicit conditions and consistent terms; retain authorization, expected results and recovery actions. |
| Strategic/context docs | Short explanations of responsibilities, interactions and guarantees; document ownership determines which details belong. |
| Change specs | Clear actors, conditions, outcomes and failures; preserve required, proposed and unresolved distinctions. |

Give each fact one owner and link to it from other contexts. Use Mermaid or D2 for relationships and lifecycles; PNGs are not documentation diagrams. The verbatim principles in DESIGN-PRINCIPLES.md are the sole permitted content duplication.
