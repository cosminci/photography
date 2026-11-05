# Saved-book print handoff

## Existing model

`AlbumRepository.get` acquires an album aggregate transactionally, while cover, photographs and selected catalog data have separate reads. Assembling existing HTTP responses cannot guarantee one saved-book snapshot.

`Spread`, `SlotBox`, `PhotoTransform` and `CoverDimensions` already own composition geometry. Editorial owners retain authored content; current presentation derives headings, attribution and gear descriptions.

## Proposed evolution

| Existing owner | Evolution and retained guarantee |
| --- | --- |
| `AlbumRepository` and its SQLite aggregate assembly | Expose existing aggregate acquisition for composition within a caller-owned transaction, reusing existing assembly rather than adding a parallel exporter reader. Existing lightweight reads retain their distinct purposes. |
| `CoverRepository`, `PhotoRepository` and selected catalog reads | Supply transaction-capable reads to the export use case; preserve current identity, ordering, selection and failure semantics. Each adapter retains its own external representation responsibility. |
| `PhotoStorage.readOriginal` | Reuse original decoding, content identity and stability checks for every print-referenced photograph after releasing the database snapshot; a transaction does not freeze files. |
| `Spread`, `SlotBox`, `PhotoTransform`, `CoverDimensions` and editorial presentation | Reuse geometry and selected content for one print projection; evolve genuinely shared presentation rules instead of copying legacy selection or geometry policy. |
| `BookExport` | Compose album, cover, photo and catalog reads through the existing `Transaction` capability, constructing its saved-book input within one read transaction. Release that transaction before original-file validation; return scoped blocking/nonblocking issues distinctly from acquisition or delivery failure, with no editing grant or album write. |
| Existing workspace | Add the export action and its pending/issues/retry lifecycle alongside existing saves; export neither commits local drafts nor resets history. |
| Existing ExtendScript entrypoints | Consume the same representation with explicit file/root selection and native preflight, retaining placement and typography behavior. Share only common decoding, source resolution and resource knowledge. |

The use case owns coherent saved acquisition; SQLite supplies transactional reads and HTTP supplies the download or explicit issues. Source checks remain filesystem guarantees, while each importer owns local availability and actual composition.

The representation contains book identity/page count, referenced originals, ordered interior content, cover content and necessary print resources. It carries no session, restoration payload or backend history.

## Review boundaries

Validation warns below 300 effective DPI and blocks empty photo slots, synopsis and chapter descriptions, while allowing empty captions.

Saved text and the JSON preserve authored characters. Native text composition removes diacritics for Ink Free synopsis/cover text and IDDragonXing chapter headings; Garamond descriptions/captions remain unchanged, and remaining unsupported glyphs warn for manual finishing without font substitution.

Native comparison uses disposable documents and an agreed small scenario and time budget; complete-album proof follows separately. Legacy rendering concerns remain hypotheses until reproduced in the actual consumer.

The approved native probe reproduced backup HEAD's crop-only mirror behavior without image reflection. The maintainer approved true horizontal reflection for new imports to match saved editor mirroring, retaining the other placement and typography behavior and leaving existing documents unchanged.
