# Book editing Undo/Redo

**Classification:** feature

**Evidence:** the legacy editor used one chronological album history. Existing editing commands can recreate a spread and reapply saved photo assignments and framing; chronological compensating edits avoid selectively skipping user actions.

## What and why

Give the open book one chronological Undo/Redo history for its content and composition. Undo reverses the latest completed edit, including deletion, rather than silently reaching past an unsupported action.

## Contracts

History belongs to the current browser page; existing content owners remain authoritative. Each reversal changes only the action's affected content or composition, preserving unrelated work.

Reversal uses ordinary editing commands to compensate for the completed edit. The browser checks current access and affected saved state before proceeding; an incompatible saved change blocks the action rather than exposing an older one.

A reversal may span several saved commands. Interrupted compensation retains its progress and explains recovery before another edit or history traversal; generated identities may change while the visible book result is restored.

Undo/Redo is best effort for operations supported by the current editor. Restoring legacy-only layouts is outside scope; a failed action stops traversal rather than skipping to an older edit.

Deliberate navigation retains history without retaining authority over previous spreads. Undo acquires any required target normally and reconciles saved state; acquisition and reconnect never replay history automatically.

## Acceptance

The [implementation design](design.md) defines owner evolution, completion evidence and compensation lifetimes separately from these observable criteria.

| ID | Observable outcome |
| --- | --- |
| A1 | Undo/Redo covers photo assignment, replacement, removal, move/swap, pan/zoom/mirror, caption corners, template changes, the spread bleed toggle and Cover spine ownership. |
| A2 | Completed caption, synopsis, chapter-description, album-name and author edits participate in the same chronological history; album renaming is available on the synopsis. |
| A3 | Chapter name, countries, dates and camera/lens selections are undoable book edits; adding a chapter lens after editing a caption undoes the lens first, then the caption. |
| A4 | Spread creation, deletion and reordering participate in that history; Undo selects the affected spread and restores a deleted spread into its original chapter and position. |
| A5 | A completed gesture or text edit creates one action; no-ops, cancellations and rejected writes create none, and a new confirmed edit clears Redo. |
| A6 | Undo/Redo names the next action and traverses completed edits in order; an unavailable or conflicting top action remains explained and never silently yields to an older action. |
| A7 | Spread navigation and Reader retain the configured number of latest completed edits, defaulting to 10; leaving the album or reloading starts a new history. |
| A8 | Unexpected ownership/session loss or expiry visibly resets history while preserving existing draft-review recovery; deliberate navigation releases do not reset it. |
| A9 | Compensation restores the visible composition, photo assignments, transforms and caption corners; recreated spreads, slots and placements may have new generated identities, which later history actions follow. |
| A10 | Another owner's lock blocks the affected reversal without takeover; missing references, reused photos, capacity violations and incompatible saved content/order remain explicit failures, with completed compensation steps retained for recovery. |
| A11 | Independently changed descriptions, authors, chapter details, gear selections and photo-owned captions are not overwritten; unrelated shared-catalog changes do not clear book history. |
| A12 | Pending or uncertain writes block traversal until reconciled; lost acknowledgement never duplicates an action, and acknowledged writes followed by refresh failure retry reads only. |
| A13 | A chapter-details Save forms one history action over the portions actually saved; partial-save recovery neither loses the saved portion nor records it again on retry. |
| A14 | Chapter creation and source-photo replacement announce that they start a new history before confirmation; cancellation and rejected changes preserve history, and ordinary reads or refreshes do not silently erase it. |
| A15 | Controls explain disabled states and retain keyboard/focus continuity on narrow layouts; native text Undo/Redo remains with the focused editor, and book traversal waits for drafts and dialogs to resolve. |
| A16 | A compact command box starts the metadata row, grouping two-drop bleed and chapter editing (album renaming on the synopsis); nonstandard bleed stays disabled and explained. |
| A17 | Aligned catalog cards show both saved cover photographs, a subtle central gutter and a compact title strip followed by the actual interior page count in small text, retaining framing and empty/loading/retryable failure states. |
| A18 | Opening an album keeps the catalog visible until the workspace is ready, without an intermediate blank page; loading failures remain visible and retryable. |
| A19 | Cover dimensions follow the interior page count; the spine-owning photograph extends continuously across the spine, with identical saved pan, zoom and mirror in catalog, editor and Reader. |
| A20 | Bleed uses one spread-wide mode: two full-height outer photos bleed top/bottom/outward; two outer stacks bleed outward; a full-height outer photo opposite a stack bleeds alone, leaving the stack inset. Saved directions matching that mode are standard, including imported openings. |
| A21 | Chapter descriptions require ownership of their chapter-opening spread for editing, saving and Undo/Redo, matching synopsis ownership of the album opening. Ownership loss retains text drafts for explicit review; chapter metadata and gear stay independently editable. |

## Doc Sync

| Owner | Enduring delta |
| --- | --- |
| specs/design.md — Editing journey / Browser editing ownership | Reversible editing is browser-local intent over authoritative content; navigation preserves intent, not authority, and restoration respects current ownership and unrelated work. Descriptions require their opening's grant, while chapter metadata and gear remain independent. Bleed selects one spread-wide mode before applying per-slot directions. |
| specs/testing.md — Confidence model | Establish chronological user intent across separately owned saves and prove reversals at their actual persistence and access boundaries. |
| specs/operational.md — Frontend configuration | Document the build-time history limit and its default. |
| specs/design.md — Cover composition | The persisted print specification catalog and current interior spread count determine cover geometry; presentation consumers share the authoritative frames. |
