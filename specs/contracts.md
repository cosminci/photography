# Photography contracts

## Provided boundaries

| Consumer | Protocol / resource | Contract |
| --- | --- | --- |
| Browser and API clients | HTTP REST under `/api/`; JSON (`application/json`) | [OpenAPI](http-api.openapi.json) owns resource paths, methods, payloads, status codes, caching and editing credentials. Aliases and credentials coordinate editing; they are not user authentication. |
| Editing browser | HTTP SSE: `GET /api/editor-session/events` (`text/event-stream`) | [OpenAPI](http-api.openapi.json) owns required headers, binding events and connection termination. Binding requires the `bound` event, not just HTTP 200; notifications require reconciliation rather than granting authority. |
| Album workspace | HTTP SSE: `GET /api/albums/{id}/photo-events` (`text/event-stream`) | [OpenAPI](http-api.openapi.json) owns subscription and inventory invalidations. Consumers subscribe before acquiring saved state; events carry neither images nor saved edits. |
| Thumbnail consumers | HTTP `GET` / `HEAD /thumbnails/{location}/{version}.jpg`; JPEG (`image/jpeg`) | [OpenAPI](http-api.openapi.json) owns versioned URLs and immutable caching. Clients use the supplied URL rather than constructing filesystem paths. |
| Browser navigation and static assets | Same-origin HTTP `GET` / `HEAD` for `/`, navigation routes and `/assets/...` | Navigation serves the packaged browser app; missing API routes and media/assets remain errors rather than returning the navigation entrypoint. |
| Readiness probes | HTTP `GET /health`; JSON `{"status":"ok"}` | [Readiness interpretation](operational.md#readiness-logs-and-metrics) defines what a successful probe establishes. |
| InDesign | JSON file from `GET /api/albums/{id}/indesign-export` | [Export JSON Schema](../backend/src/main/resources/indesign-book.schema.json) owns the saved-book handoff. [Print handoff](operational.md#print-handoff) requires originals available on the operator's machine; deployment thumbnail paths are not print resources. |

A reverse proxy must preserve editing headers and unbuffered SSE, including transport heartbeat comments, and keep API and immutable-media caching policies separate.

## Consumed boundaries

| Dependency | Protocol / format | Contract |
| --- | --- | --- |
| SQLite | SQL against an embedded local database; Flyway versioned SQL migrations | [Migrations](../backend/src/main/resources/db/) own persisted representation and seeded catalogs. Runtime connections enforce foreign keys; Flyway retains applied-schema history. [Operations](operational.md#recovery-and-maintenance) owns compatible restore and migration procedures. |
| Original-photo archive | Read-only local filesystem; JPEG files in the selected chapter directory | Sources refer to backend-visible paths, not the browser's filesystem. Enumeration reads that directory's files; unreadable, undecodable or changing originals produce processing failures rather than usable photos. |
| Managed thumbnails | Writable local filesystem with atomic rename and durable file/directory writes; JPEG content versions | The volume must support those operations. Reads must match the requested content version; [publication and recovery](design.md#thumbnail-publication-and-recovery) owns coordination with saved references. |
| Photo representation | JPEG with EXIF metadata | Import interprets EXIF orientation for displayed dimensions and generates orientation-correct JPEG previews. Preview generation does not modify originals. |
| Browser profile storage | IndexedDB and Web Storage (`localStorage`), scoped to the browser origin | IndexedDB retains browser identity; localStorage remembers session details. Identification-storage failure blocks entry rather than assigning a replacement identity. |

Runtime settings and deployment mounts belong to [operations](operational.md).
