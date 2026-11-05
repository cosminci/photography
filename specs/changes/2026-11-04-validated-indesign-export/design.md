# Saved-book print handoff

## Existing model

`AlbumRepository.get` acquires an album aggregate transactionally, while cover, photographs and selected catalog data have separate reads. Export composes these existing repository APIs and accepts their independent read lifetimes.

`Spread`, `SlotBox`, `PhotoTransform` and `CoverDimensions` already own composition geometry. Editorial owners retain authored content; current presentation derives headings, attribution and gear descriptions.

## Proposed evolution

| Existing owner | Evolution and retained guarantee |
| --- | --- |
| `AlbumRepository.get`, `CoverRepository.read`, `PhotoRepository.listChapter`, `GearRepository.readCatalog` | Reuse unchanged read APIs and trust their domain-valid output. Each adapter retains its own acquisition and external representation responsibility. |
| `PhotoStorage.readOriginal` | Reuse original decoding, content identity and stability checks for every print-referenced photograph; filesystem changes remain distinct from saved-data validity. |
| `Spread`, `SlotBox`, `PhotoTransform`, `CoverDimensions` and editorial presentation | Reuse geometry and selected content for one print projection; evolve genuinely shared presentation rules instead of copying legacy selection or geometry policy. |
| `BookExport` | Compose existing repository reads, then check print readiness and return scoped blocking/nonblocking issues distinctly from acquisition or delivery failure. Export acquires no editing grant and performs no album write. |
| Existing workspace | Add the export action and its pending/issues/retry lifecycle alongside existing saves; export neither commits local drafts nor resets history. |
| Existing ExtendScript entrypoints | Consume the same representation with explicit file/root selection and native preflight, retaining placement and typography behavior. Share only common decoding, source resolution and resource knowledge. |

No new reader interfaces or cross-repository transaction are introduced for export. The use case owns composition and print readiness, HTTP owns the JSON projection, and each importer owns local availability and actual composition.

The representation contains book identity/page count, referenced originals, ordered interior content, cover content and necessary print resources. It carries no session, restoration payload or backend history.

## Review boundaries

Validation warns below 300 effective DPI and blocks empty photo slots, synopsis and chapter descriptions, while allowing empty captions.

Saved text and the JSON preserve authored characters. Native text composition removes diacritics for Ink Free synopsis/cover text and IDDragonXing chapter headings; Garamond descriptions/captions remain unchanged, and remaining unsupported glyphs warn for manual finishing without font substitution.

Native comparison uses disposable documents and an agreed small scenario and time budget; complete-album proof follows separately. Legacy rendering concerns remain hypotheses until reproduced in the actual consumer.

The approved native probe reproduced backup HEAD's crop-only mirror behavior without image reflection. The maintainer approved true horizontal reflection for new imports to match saved editor mirroring, retaining the other placement and typography behavior and leaving existing documents unchanged.
