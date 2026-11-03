# Editing and presentation finishing

**Classification:** feature

**Grounded in:** read-only inspection of the populated application confirmed blank cover cards, outline-only navigation, absent spine controls and caption offsets that vary with slot height.

## What and why

Make saved compositions recognizable in navigation, expose cover ownership and finish bleed/caption presentation without changing layout authorship.

## Bleed behavior

One spread toggle enables every eligible photo slot; disabling clears the spread's saved directions.

| Slot geometry | Enabled directions |
| --- | --- |
| Inner, including a photo beside outer text | None |
| Full-height leftmost | Top, bottom, left |
| Full-height rightmost | Top, bottom, right |
| Left outer stack | Left on every member |
| Right outer stack | Right on every member |

Outer and full-height refer to the existing content margins, including already expanded edges; stacks share an outer boundary even when widths differ.

Visible edges extend to the spread boundary when enabled and retract to the existing seven-point margin when disabled; saved template boxes and transforms stay unchanged.

Imported and newly created spreads behave identically; an existing partial selection displays as mixed, and its next toggle enables the complete eligible set.

## Model evolution

| Existing owner | Proposed change | Contract and consumers |
| --- | --- | --- |
| `Spread`, `SpreadSlot.Photo`, `SpreadEdge`, `SlotBox` | Add pure derivations for the enabled direction plan, visible photo boxes and non-bleeding content boxes. | Keep explicit directions on each photo slot; no stored spread flag or template eligibility fields. The same domain rules supply command decisions, saved-resource delivery and editor/navigator/Reader framing. |
| `SpreadManager` | Add `setBleed(album: AlbumId, spread: SpreadId, enabled: Boolean): F[SetBleedResult]`. | Results: saved spread, missing album/spread, rejected selection or failure. Decide from current geometry under existing mutation coordination; reuse `SpreadRepository.replace` to commit all directions together without changing slot/placement identities. |
| `Cover`, `SpineOwner`, `CoverManager` | Keep the domain shape; add `setSpineOwner(album: AlbumId, owner: SpineOwner): F[SetSpineOwnerResult]`. | Results: saved cover, missing cover or failure. Coordinate ownership changes with placement edits; retain both sides, photographs, transforms and intrinsic bleed. Editor and Reader show the saved owner. |
| `CoverRepository` | Add `setSpineOwner(album: AlbumId, owner: SpineOwner): Db[SetSpineOwnerResult]`. | Update only the existing cover owner, returning updated or missing. SQLite persists the supplied decision atomically; existing placement replacement continues preserving ownership. |
| `PhotoRepository` | Add `find(photo: PhotoId): Db[FindResult]` using its existing photo representation authority. | Results: found `Photo`, missing or failure. Supplies an individual photo read without loading unrelated chapter inventories. |
| `PhotoManager` | Add `read(photo: PhotoId): F[ReadResult]`, backed by `PhotoRepository.find`. | Results: found `Photo`, missing or failure. Expose the photo resource through the existing photo API owner; no cover dependency or combined cover response. |

`CoverManager.read` and its response stay unchanged; a cover card reads the saved cover, then its referenced front photo, with independent failure handling.

Saved spread resources gain per-slot directions and derived boxes; the browser reuses acquired spreads/photos and shares framing across navigation, editing and Reader.

New commands accept only spread bleed selection or cover ownership; target validation and existing atomic save/recovery guarantees remain authoritative.

## Acceptance

| ID | Observable outcome |
| --- | --- |
| A1 | Spread navigation shows saved photo identities, crop, zoom and mirror, including newly acknowledged edits. |
| A2 | Album cards show the saved front-cover photograph and framing, refreshed on catalog re-entry. |
| A3 | Empty, loading, failed data acquisition and failed thumbnail delivery remain distinguishable, with explicit retry. |
| A4 | Switching either cover spine/gutter owner persists through reload and Reader without changing cover content. |
| A5 | Bleed follows the direction table, including whole stacks, empty eligible slots and identical imported/new behavior. |
| A6 | Enable/disable visibly extends/retracts frames; saved directions, reload and Reader agree. |
| A7 | Viewing mixed bleed sends no mutation; enabling with no eligible slot is rejected with an explanation. |
| A8 | Adjacent captions align within one CSS pixel at shared content edges, across orientations and bleed states. |
| A9 | Caption text and corners remain unchanged; top corners align first baselines and bottom corners align last baselines, including wrapped text. |
| A10 | Pending or failed saves retain usable feedback; acknowledged-refresh failures retry reads only, and uncertain writes reconcile before another confirmed mutation. |
| A11 | Preview updates preserve selection and scrolling; new controls support keyboard/focus continuity and narrow layouts with explained unavailable states. |

Locking is a separate feature; browser-local undo remains pending its actual contracts and approved reversible operations, not excluded or claimed complete here.

## Doc Sync

| Owner | Enduring delta |
| --- | --- |
| `specs/design.md` — Album model / Editing journey | Spread-level bleed interaction with per-slot persistence, shared saved previews, caption anchoring and editable cover ownership. |
| `specs/http-api.openapi.json` | New commands, individual photo read, enriched spread reads, derived-frame representations and failure/acknowledgement semantics. |
