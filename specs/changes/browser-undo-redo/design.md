# Browser-local compensating history

## Existing model

The workspace already coordinates authoritative content saves, placement drafts, navigation and editing access. Content saves distinguish acknowledged publication from uncertain writes; placement sessions retain framing drafts when ownership changes.

Existing editing APIs create identities and persist the book; they do not expose history or exact restoration. The approved design reuses those commands, accepts regenerated identities and preserves existing session and target-grant enforcement.

## Incremental delivery

Content, photo-composition and chapter-details/gear history share one bounded chronology. Spread-structure compensation follows in a separate delivery.

Until those integrations ship, a confirmed changed edit in a deferred category visibly starts a new history rather than leaving older edits traversable. Pending or uncertain edits block traversal; read recovery keeps history for an unchanged outcome and resets it for a changed deferred edit, without repeating the mutation.

Chapter-details Save groups the portions actually saved, retaining partial progress and recording recovered completions once. Chapter creation and source-photo replacement announce their permanent history boundary before saving; unchanged details, rejected saves and cancellations preserve existing history.

## Model evolution

| Existing owner / contract | Evolution and retained responsibility | Supplier, consumer and proof boundary |
| --- | --- | --- |
| `AlbumWorkspace` editing coordination | Own one `BookHistory` per mounted album, bounded by application configuration (default 10); retain it across selection and Reader, reset on unexpected access loss or album exit. | Composition parses `VITE_BOOK_HISTORY_LIMIT` once and supplies the limit; save owners supply completed edits and controls consume next-action labels. |
| `ContentSave<T, Saved>` draft and recovery lifecycle | Separate exactly-once `onCompleted` from repeatable `onSaved` publication; an optional typed `rebase` retains a recovered authoritative completion baseline independently of the visible draft. | Text, shared captions and authors map their saved representation to the before-state; chapter saves keep command and authoritative book-state evidence with their existing owners. |
| `PlacementSession` acknowledged commands and retained gestures | Publish each completed gesture or command once; retain its before-state, command and captured authority/generation through uncertain readback. | Canvas and composition controls supply edits; history receives a completion only while its captured lifetime remains valid, including loss followed by reacquisition. |
| `ChapterDialog` metadata and gear saves | Capture the session-loss revision for both portions before Save; preserve retained recovery evidence and group only completions from the same revision. | Metadata acknowledgement after loss cannot start gear automatically; an explicit reviewed gear save uses current chapter details as its unchanged baseline. `ChapterCompensation` resumes unfinished portions, while creation/source replacement establishes a new history boundary. |
| Existing content and composition APIs | Reuse reads and ordinary writes through `ContentCompensation`, `PlacementCompensation` and `SpreadCompensation`; history adds no backend storage or restoration contract. | Resource owners compare affected saved state, acquire normal authority and issue commands; existing HTTP/SQLite boundaries remain authoritative. |

`HistoryAction.apply(direction)` returns saved, blocked, uncertain or conflict; `reconcile(direction)` only reads and can additionally report unchanged. A confirmed new edit clears Redo, while a blocked or unresolved newest action never exposes an older action.

Placement compensation compares visible composition independently of generated identities and reapplies assignment, framing and presentation with ordinary commands. Spread compensation retains acknowledged replacement identities, and workspace identity aliases carry those replacements into earlier actions and chapter orders.

## Failure and lifetime contract

Original-save uncertainty and inverse uncertainty retain separate receipts. Unknown spread creation never guesses an identity or reissues creation automatically; known partial compensation resumes only through an explicit same-direction retry.

Read recovery preserves the user's draft while establishing the authoritative baseline for any subsequent explicit save. An acknowledged write followed by publication failure retries reads, without recording or issuing the write again.

Captured operation lifetimes fence late original-save completions as well as history traversal. Normal selection and Reader release authority deliberately; unexpected loss resets history and neither reacquisition nor reconnect replays it.

Chapter-description mutations use EditingAccess's OwnChapterOpening policy, resolving the first matching chapter member from canonical album composition under the same coordination as authorization and saving. The resolved opening requires its exact current grant; missing membership fails without writing.

Description controls and retained TextEditor drafts use the selected opening's ownership reason and authority; their history compensation acquires that opening normally before comparing and writing. Album-name editing remains independently writable through its existing session policy.

The workspace derives canvas presentation from Reader selection or unavailable ownership, reusing the existing canvas Reader path for slots, text and captions. This presentation does not enter fullscreen, release grants, hide independent metadata controls or discard retained drafts.

ChapterMetadataManager retains transaction cancellation/uncertainty handling without reacquiring EditingAccess's mutex. Synopsis retains OwnAlbumOpening, while chapter metadata, gear and import operations retain their independent access and coordination policies.

Compensation is best effort, not an atomic server transaction: independently writable content can change between comparison and write. Visible progress and explicit failure remain necessary, while whole-album snapshots, backend history and legacy-layout repair remain outside this design.

The authoritative mixed-bleed state disables only the spread bleed toggle, preserving its saved framing without introducing another bleed classifier. Ordinary standard bleed and other supported editing operations retain their existing owners.

Spread owns selection of one coordinated bleed mode before classification or mutation: any full-height outer photo selects top/bottom/outward bleed for full-height outer photos only; otherwise eligible outer stacks bleed outward together. SlotBox retains local edge geometry, while Spread compares and applies the same spread-wide selection. Imported matching directions classify as enabled without rewriting saved boxes, identities, transforms or directions.

## Presentation refinements

The catalog preview owner orchestrates one cover read and the referenced front/back photo reads, retaining shared-photo identity and saved framing. Each face owns its media retry; cover-data retry remains with their shared acquisition.

The same cover response supplies actual interior page count, independent of the selected specification's page limit; catalog captions show it after the title. Equal catalog viewports align the cards while each cover surface retains its own physical proportions and spine width.

The maintainer-approved cover correction evolves `CoverRepository.Composition` with `CoverDimensions`; its existing SQLite supplier acquires current interior spread count and persisted Saal specifications in the same read transaction. `CoverSpecification` validates the reference data, and the domain derives the intrinsic print geometry: two pages per interior spread, the smallest sufficient specification, capped at the largest available specification.

The specification catalog preserves the legacy Saal artwork dimensions at 300 dpi; it remains reference data alongside existing template catalogs, rather than a second frontend print policy. Missing or invalid specifications fail the cover read explicitly.

`CoverManager` retains composition and mutation ownership; all cover acknowledgements expose the same geometry through the existing HTTP/OpenAPI representation. Spine ownership selects which photo frame includes the spine without changing saved transforms, slot identities or placements.

Catalog, CoverCanvas and Reader consume the authoritative frames through one cover surface; crop projection receives each actual frame's dimensions, and subtle spine-edge guides overlay the owning photograph. Catalog re-entry uses its existing fresh acquisition, while spread-count changes invalidate the workspace's acquired cover dimensions through its existing refresh owner.

Application navigation retains the catalog while the existing workspace acquisition and initial editing-ownership reconciliation finish. The workspace reports initial readiness without changing refresh, ownership or failure policies; failed initial reads reveal their existing retry controls.

CompositionControls retains bleed mutation, availability and focus ownership, rendered as a compact droplet before chapter metadata. Cover spine editing retains its existing location.

The metadata command box groups bleed and the existing chapter editor at the row's start; synopsis renaming reuses TextEditor/ContentSave recovery and ordinary album rename, publishing the acknowledged title through the existing album owner. Album-name compensation compares the authoritative title and preserves unrelated album content.

The synopsis owns the album-name editing entry point; catalog actions retain deletion, while the catalog dialog owns album creation and deletion only. The bleed icon uses two separated droplets with clear space between their strokes.

`VITE_BOOK_HISTORY_LIMIT=25 npm --prefix frontend run build` configures the retained action limit for that frontend build; development reads the same setting when its server starts. An absent value defaults to 10, while nonpositive, fractional or unsafe values produce an explicit configuration error.
