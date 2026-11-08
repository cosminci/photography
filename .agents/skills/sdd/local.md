# Photography ownership and safety

## Start and validate

Run application commands from the repository root. Follow [Contributing](../../../CONTRIBUTING.md) for setup and gates, and [operations](../../../specs/operational.md#run-locally) for the local launcher; it installs locked frontend dependencies and reports ready URLs.

Keep scope, the workflow revision, loaded sources and check evidence in ignored `.agent-work/<slug>`. Current specs own product knowledge; executable schemas, migrations, configuration and ports own precise definitions.

Check changed backend, frontend and pipeline boundaries with their Dagger component gates. Every change runs `dagger call verify`; use `--all` when shared impact is uncertain.

Pipeline-module changes also run `npm ci --prefix .dagger && dagger develop && npm --prefix .dagger run verify`. Pre-push/CI uses `bash scripts/verify <base>`; enable `.githooks` and follow [pipeline operations](../../../ci/specs/operational.md#local-push-verification) for candidate and baseline requirements.

## Storage and deployment safety

Use isolated SQLite, original-photo and thumbnail paths from [application configuration](../../../backend/src/main/resources/application.conf). Originals are read-only; application state and thumbnails are writable.

Preserve originals and retained spike/NAS state. Replacement requires explicit owner authorization; identify disposable databases before recreation. Keep secrets in local environment/configuration and stop only processes owned by the current work.

| Lifecycle | Obligation |
| --- | --- |
| Before the first NAS deployment | Evolve V1 schema/V2 data with ordinary migrated repository seam tests; retained spike data requires authorization. |
| After deployment | Preserve applied migrations and introduce versioned changes with upgrade evidence. |
| Running and recovering | Use one backend; preserve SQLite and referenced thumbnails consistently. |

Read [operations](../../../specs/operational.md) before startup or recovery changes, and the [NAS runbook](../../../unraid/README.md) before publication, installation, updates or seeding. Historical approvals do not authorize a new production action.

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

Use diagrams for component relationships and lifecycles, and tables for comparisons. Link exact schemas, settings and source definitions rather than copy their inventories.
