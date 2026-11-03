# Book editing Undo/Redo

**Classification:** feature

**Evidence:** the legacy editor used one chronological album history. Current editing commands cannot exactly restore deleted spreads, discarded composition or mixed bleed; selectively recording edits would make Undo unpredictable.

## What and why

Give the open book one chronological Undo/Redo history for its content and composition. Undo reverses the latest completed edit, including deletion, rather than silently reaching past an unsupported action.

## Contracts

History belongs to the current browser page; existing content owners remain authoritative. Each reversal changes only the action's affected content or composition, preserving unrelated work.

Restoration validates current access, affected saved state and references before committing atomically. An incompatible saved change rejects the reversal without partially applying it or exposing an older action instead.

Deliberate navigation retains history without retaining authority over previous spreads. Undo acquires any required target normally and reconciles saved state; acquisition and reconnect never replay history automatically.

## Acceptance

| ID | Observable outcome |
| --- | --- |
| A1 | Undo/Redo covers photo assignment, replacement, removal, move/swap, pan/zoom/mirror, caption corners, template changes, mixed bleed and Cover spine ownership. |
| A2 | Completed caption, synopsis, chapter-description and author edits participate in the same chronological history. |
| A3 | Chapter name, countries, dates and camera/lens selections are undoable book edits; adding a chapter lens after editing a caption undoes the lens first, then the caption. |
| A4 | Spread creation, deletion and reordering participate in that history; Undo selects the affected spread and restores a deleted spread into its original chapter and position. |
| A5 | A completed gesture or text edit creates one action; no-ops, cancellations and rejected writes create none, and a new confirmed edit clears Redo. |
| A6 | Undo/Redo names the next action and traverses completed edits in order; an unavailable or conflicting top action remains explained and never silently yields to an older action. |
| A7 | Spread navigation and Reader retain the latest 50 completed edits within the open album; leaving the album or reloading starts a new history. |
| A8 | Unexpected ownership/session loss or expiry visibly resets history while preserving existing draft-review recovery; deliberate navigation releases do not reset it. |
| A9 | Composition restoration preserves original spread, slot and placement identities, saved geometry, transforms, caption corners and per-slot bleed, including imported compositions. |
| A10 | Another owner's lock blocks the affected reversal without takeover; missing references, reused photos, capacity violations and incompatible saved content/order reject without partial changes. |
| A11 | Independently changed descriptions, authors, chapter details, gear selections and photo-owned captions are not overwritten; unrelated shared-catalog changes do not clear book history. |
| A12 | Pending or uncertain writes block traversal until reconciled; lost acknowledgement never duplicates an action, and acknowledged writes followed by refresh failure retry reads only. |
| A13 | A chapter-details Save forms one history action over the portions actually saved; partial-save recovery neither loses the saved portion nor records it again on retry. |
| A14 | Chapter creation and source-photo replacement announce that they start a new history before confirmation; cancellation and rejected changes preserve history, and ordinary reads or refreshes do not silently erase it. |
| A15 | Controls explain disabled states and retain keyboard/focus continuity on narrow layouts; native text Undo/Redo remains with the focused editor, and book traversal waits for drafts and dialogs to resolve. |

## Doc Sync

| Owner | Enduring delta |
| --- | --- |
| specs/design.md — Editing journey / Browser editing ownership | Reversible editing is browser-local intent over authoritative content; navigation preserves intent, not authority, and restoration respects current ownership and unrelated work. |
| specs/http-api.openapi.json | Exact composition and spread restoration plus affected-content comparisons, including atomic rejection and uncertain outcomes. |
| specs/testing.md — Confidence model | Establish chronological user intent across separately owned saves and prove reversals at their actual persistence and access boundaries. |
