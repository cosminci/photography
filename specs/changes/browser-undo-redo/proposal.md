# Browser-local Undo/Redo

**Classification:** feature

**Evidence:** current editing grants protect mutation admission, but ordinary writes neither compare historical values nor recreate removed placement identities; deletion and template replacement discard composition needed for recovery.

## What and why

Let editors reverse hard-to-reconstruct work using conventional current-target Undo/Redo stacks and separate deleted-spread recovery. Retained history supplies recovery values; saved state and current editing authority decide whether restoration is allowed.

## Contracts

Undo expects the affected saved state to equal the action's after-state; Redo expects its before-state. The content/composition owner checks that condition and commits the replacement atomically, preserving unrelated state.

Ownership loss retains history values but invalidates their authority. Restoration resumes only after ordinary saved-state reconciliation and a new explicit user action; conflicting or unavailable state blocks the top action with an explanation.

Deleted-spread recovery restores into its original chapter at the former photo-spread position, bounded by the current chapter's end. It preserves surviving order and rejects unavailable references, occupied photos, capacity violations or changed observed order without partial recovery.

## Model evolution

| Existing concept / operation / port | Proposed evolution | Invariants, inputs/results and failure semantics | Migrated suppliers / consumers |
| --- | --- | --- | --- |
| Browser editing and save coordination | Retain typed before/after actions keyed by album, target and actual content owner; persist current-target stacks for the tab lifetime. | Record confirmed changes once; transfer an existing action on restoration, retaining unresolved outcomes until reconciliation. | PlacementSession, text saves and structural/template editing supply confirmed results; Undo/Redo controls consume the selected target's stack. |
| AlbumManager.saveSynopsis, ChapterMetadataManager.saveDescription, PhotoManager.saveCaption and their repository operations | Add an explicit `maybeExpected: Option[String]` and a `Stale` result to existing saves. | Absent expectation retains ordinary-save behavior; a supplied empty value is still a condition. SQLite comparison and update are one atomic operation, including independently writable descriptions. | Existing HTTP/text clients retain ordinary saves; history supplies expected current text and the recorded replacement. Captions remain photo-owned. |
| SpreadManager and CoverManager | Add owner-specific `restore` operations with typed expected/proposed affected state and companion-owned restoration results. | Placement variants preserve slot/photo/placement identities, transforms and caption corners; validate current membership, exclusive photo use and supported geometry before committing. | Composition HTTP owners translate restoration requests; browser history supplies recorded values; existing repositories commit supplied decisions. |
| SpreadManager restoration and SpreadRepository.replace | Include bleed and existing-template restoration variants. | Bleed restores exact recorded directions, including mixed state; template restoration compares the current spread and restores recorded slots, geometry and placements under the same identity and valid context. | Browser composition/template history consumes saved spreads; photo-owned captions remain outside composition replacement. |
| CoverManager restoration and CoverRepository | Include a conditional spine-owner restoration variant. | Compare and update ownership while preserving both photographs and their framing; CoverManager.read and its response remain unchanged. | Cover history uses the saved owner; existing cover persistence applies the supplied change. |
| SpreadManager, SpreadRepository.insert/reorder and chapter composition | Add `restoreDeleted(album, chapter, recovery): F[RestoreDeletedResult]`, carrying the saved photo spread, former position and observed chapter order. | Require absent spread identity and current album/chapter; decide capacity, references and photo availability under shared coordination, then insert and reorder in one transaction. | Existing deletion coordination retains the confirmed recovery record; separate recovery controls consume it. |
| EditingAccess and route policies | Reuse current relationship validation and shared mutation admission for restorations. | Existing targets require current grants; deleted-target insertion requires an active tab. Distinguish stale state, unavailable authority, missing resources, invalid/conflicting requests, failure and uncertain acknowledgement. | Existing HTTP ownership enforcement admits commands; content managers retain business decisions and repositories retain atomic persistence. |

These are conditional writes through existing content owners, not backend history or whole-album restoration. History retention adds no permission, forced takeover or automatic cross-target acquisition.

## Acceptance

| ID | Observable outcome |
| --- | --- |
| A1 | Current-target Undo/Redo restores synopsis, chapter descriptions, photo captions, pan/zoom/mirror, caption corners, bleed, spine ownership, photo assignment/removal/replacement/move and existing template switches. |
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
| specs/http-api.openapi.json | Conditional text saves, typed composition restoration and deleted-spread recovery, including stale, conflict and uncertain outcomes. |
| specs/testing.md — Confidence model | Prove history lifecycle in browser coordination and conditional restoration at actual persistence/ownership boundaries. |
