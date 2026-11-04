# Saved-book print handoff

## Existing model

`AlbumRepository.get` acquires an album aggregate transactionally, while cover, photographs and selected catalog data have separate reads. Assembling existing HTTP responses cannot guarantee one saved-book snapshot.

`Spread`, `SlotBox`, `PhotoTransform` and `CoverDimensions` already own composition geometry. Editorial owners retain authored content; current presentation derives headings, attribution and gear descriptions.

## Proposed evolution

| Existing owner | Evolution and retained guarantee |
| --- | --- |
| `AlbumRepository` and its SQLite aggregate assembly | Evolve acquisition to supply the complete saved book within one read transaction, reusing existing assembly rather than adding a parallel exporter reader. Existing lightweight reads retain their distinct purposes. |
| `CoverRepository`, `PhotoRepository` and selected catalog reads | Compose their transaction-capable reads into that acquisition; preserve current identity, ordering, selection and failure semantics. The export consumer receives saved facts together. |
| `PhotoStorage.readOriginal` | Reuse original decoding, content identity and stability checks for every print-referenced photograph after releasing the database snapshot; a transaction does not freeze files. |
| `Spread`, `SlotBox`, `PhotoTransform`, `CoverDimensions` and editorial presentation | Reuse geometry and selected content for one print projection; evolve genuinely shared presentation rules instead of copying legacy selection or geometry policy. |
| New export operation | Coordinate saved acquisition, complete validation and publication of a versioned print-only representation. Return scoped blocking/nonblocking issues distinctly from acquisition or delivery failure; acquire no editing grant and write no album state. |
| Existing workspace | Add the export action and its pending/issues/retry lifecycle alongside existing saves; export neither commits local drafts nor resets history. |
| Existing ExtendScript entrypoints | Consume the same representation with explicit file/root selection and native preflight, retaining placement and typography behavior. Share only common decoding, source resolution and resource knowledge. |

SQLite supplies the coherent saved acquisition; HTTP supplies the download or explicit issues. Source checks remain filesystem guarantees, while each importer owns local availability and actual composition.

The representation contains book identity/page count, referenced originals, ordered interior content, cover content and necessary print resources. It carries no session, restoration payload or backend history.

## Review boundaries

Validation thresholds, intentional empty content and authored-character policy remain proposal decisions, not implementation defaults. Exact JSON and HTTP representations belong to executable contracts after approval.

Native comparison uses disposable documents and an agreed small scenario and time budget; complete-album proof follows separately. Legacy rendering concerns remain hypotheses until reproduced in the actual consumer.
