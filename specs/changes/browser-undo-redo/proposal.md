# Browser-local Undo/Redo

**Classification:** feature

**Evidence:** removing photographs, replacing templates and deleting spreads can discard carefully composed work; exclusive editing alone does not make restoration safe after intervening edits.

## What and why

Let editors reverse hard-to-reconstruct work using conventional current-target Undo/Redo stacks and separate deleted-spread recovery. Retained history supplies recovery values; saved state and current editing authority decide whether restoration is allowed.

## Contracts

Undo expects the affected saved state to equal the action's after-state; Redo expects its before-state. Restoration either applies completely to matching saved state or leaves it unchanged, preserving unrelated work.

Ownership loss retains history values but invalidates their authority. Restoration resumes only after ordinary saved-state reconciliation and a new explicit user action; conflicting or unavailable state blocks the top action with an explanation.

Deleted-spread recovery restores into its original chapter at the former photo-spread position, bounded by the current chapter's end. It preserves surviving order and rejects unavailable references, occupied photos, capacity violations or changed observed order without partial recovery.

## Acceptance

| ID | Observable outcome |
| --- | --- |
| A1 | Current-target Undo/Redo restores synopsis, chapter descriptions, photo captions, pan/zoom/mirror, caption corners, bleed including mixed directions, spine ownership, photo assignment/removal/replacement/move and existing template switches. |
| A2 | Successful Undo/Redo transfers one action between stacks without duplication or unrelated changes; a new confirmed edit clears Redo. |
| A3 | History survives album/spread navigation and reloads until the application tab closes; author editing, chapter/spread creation, gear and chapter metadata retain their workflows without history. |
| A4 | Deleted photo-spread recovery restores original identities, geometry and composition while preserving surviving order; missing references, occupied photos, capacity and stale-order conflicts reject atomically. |
| A5 | Another owner's lock blocks restoration; expiry, release, loss and reacquisition never replay history or revive former authority. |
| A6 | An affected-state mismatch blocks the top action without overwriting newer work or skipping it, including independent description edits and captions whose placement/ownership changed. |
| A7 | Pending and uncertain saves block history actions until reconciled, including across reload; an acknowledged write followed by refresh failure records its action once and retries reads only. |
| A8 | Controls explain unavailable states, retain keyboard/focus continuity and work on narrow layouts; shortcuts preserve native text-entry Undo/Redo. |

## Doc Sync

| Owner | Enduring delta |
| --- | --- |
| specs/design.md — Editing journey / Browser editing ownership | Historical recovery values are separate from saved-state authority; restoration is conditional on current state and current access. |
| specs/http-api.openapi.json | Conditional restoration and deleted-spread recovery guarantees, including conflict and uncertain outcomes. |
| specs/testing.md — Confidence model | Prove history lifecycle in browser coordination and conditional restoration at actual persistence/ownership boundaries. |
