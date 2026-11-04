# Browser-local compensating history

## Existing model

The workspace already coordinates authoritative content saves, placement drafts, navigation and editing access. Content saves distinguish acknowledged publication from uncertain writes; placement sessions retain framing drafts when ownership changes.

Existing editing APIs create identities and persist the book; they do not expose history or exact restoration. The approved design reuses those commands, accepts regenerated identities and preserves existing session and target-grant enforcement.

## Model evolution

| Existing owner / contract | Evolution and retained responsibility | Supplier, consumer and proof boundary |
| --- | --- | --- |
| `AlbumWorkspace` editing coordination | Own one `BookHistory` per mounted album, bounded by application configuration (default 10); retain it across selection and Reader, reset on unexpected access loss or album exit. | Composition parses `VITE_BOOK_HISTORY_LIMIT` once and supplies the limit; save owners supply completed edits and controls consume next-action labels. |
| `ContentSave<T, Saved>` draft and recovery lifecycle | Separate exactly-once `onCompleted` from repeatable `onSaved` publication; an optional typed `rebase` retains a recovered authoritative completion baseline independently of the visible draft. | Text, shared captions and authors map their saved representation to the before-state; chapter saves keep command and authoritative book-state evidence with their existing owners. |
| `PlacementSession` acknowledged commands and retained gestures | Publish each completed gesture or command once; retain its before-state, command and captured authority/generation through uncertain readback. | Canvas and composition controls supply edits; history receives a completion only while its captured lifetime remains valid, including loss followed by reacquisition. |
| `ChapterDialog` metadata and gear saves | Capture the session-loss revision for both portions before Save; preserve retained recovery evidence and group only completions from the same revision. | Metadata acknowledgement after loss cannot start gear automatically; an explicit reviewed gear save uses current chapter details as its unchanged baseline. `ChapterCompensation` resumes unfinished portions, while creation/source replacement establishes a new history boundary. |
| Existing content and composition APIs | Reuse reads and ordinary writes through `ContentCompensation`, `PlacementCompensation` and `SpreadCompensation`; no backend, storage, OpenAPI or lock contract changes. | Resource owners compare affected saved state, acquire normal authority and issue commands; existing HTTP/SQLite boundaries remain authoritative. |

`HistoryAction.apply(direction)` returns saved, blocked, uncertain or conflict; `reconcile(direction)` only reads and can additionally report unchanged. A confirmed new edit clears Redo, while a blocked or unresolved newest action never exposes an older action.

Placement compensation compares visible composition independently of generated identities and reapplies assignment, framing and presentation with ordinary commands. Spread compensation retains acknowledged replacement identities, and workspace identity aliases carry those replacements into earlier actions and chapter orders.

## Failure and lifetime contract

Original-save uncertainty and inverse uncertainty retain separate receipts. Unknown spread creation never guesses an identity or reissues creation automatically; known partial compensation resumes only through an explicit same-direction retry.

Read recovery preserves the user's draft while establishing the authoritative baseline for any subsequent explicit save. An acknowledged write followed by publication failure retries reads, without recording or issuing the write again.

Captured operation lifetimes fence late original-save completions as well as history traversal. Normal selection and Reader release authority deliberately; unexpected loss resets history and neither reacquisition nor reconnect replays it.

Compensation is best effort, not an atomic server transaction: independently writable content can change between comparison and write. Visible progress and explicit failure remain necessary, while whole-album snapshots, backend history and legacy-layout repair remain outside this design.

The authoritative mixed-bleed state disables only the spread bleed toggle, preserving its saved framing without introducing another bleed classifier. Ordinary standard bleed and other supported editing operations retain their existing owners.

## Presentation refinements

The catalog preview owner orchestrates one cover read and the referenced front/back photo reads, retaining shared-photo identity and saved framing. Each face owns its media retry; cover-data retry remains with their shared acquisition.

Application navigation retains the catalog while the existing workspace acquisition and initial editing-ownership reconciliation finish. The workspace reports initial readiness without changing refresh, ownership or failure policies; failed initial reads reveal their existing retry controls.

CompositionControls retains bleed mutation, availability and focus ownership, rendered as a compact droplet before chapter metadata. Cover spine editing retains its existing location.

`VITE_BOOK_HISTORY_LIMIT=25 npm --prefix frontend run build` configures the retained action limit for that frontend build; development reads the same setting when its server starts. An absent value defaults to 10, while nonpositive, fractional or unsafe values produce an explicit configuration error.
