# Photography contracts

## Provided boundaries

| Boundary | Protocol / resource | Definition |
| --- | --- | --- |
| API | HTTP REST / JSON under `/api/` | [OpenAPI](http-api.openapi.json) |
| Editing connection | SSE: `GET /api/editor-session/events` | [OpenAPI](http-api.openapi.json#/paths/~1api~1editor-session~1events/get) |
| Photo updates | SSE: `GET /api/albums/{id}/photo-events` | [OpenAPI](http-api.openapi.json#/paths/~1api~1albums~1{id}~1photo-events/get) |
| Thumbnails | HTTP / JPEG: `GET`, `HEAD /thumbnails/{location}/{version}.jpg` | [OpenAPI](http-api.openapi.json#/paths/~1thumbnails~1{location}~1{version}.jpg) |
| Browser app | HTTP: `GET`, `HEAD /`, navigation routes and `/assets/...` | Navigation returns app HTML. Missing API routes, media and assets return HTTP errors. |
| Readiness | HTTP / JSON: `GET /health` | `200 {"status":"ok"}`; [probe limits](operational.md#readiness-logs-and-metrics) |
| InDesign handoff | JSON: `GET /api/albums/{id}/indesign-export` | [JSON Schema](../backend/src/main/resources/indesign-book.schema.json) |

## Consumed boundaries

| Dependency | Protocol / format | Definition or constraint |
| --- | --- | --- |
| SQLite | Embedded SQL; Flyway migrations | [Migrations](../backend/src/main/resources/db/); foreign keys enabled |
| Originals | Read-only filesystem; JPEG / EXIF | Backend-visible chapter directory; nonrecursive discovery |
| Thumbnails | Writable filesystem; JPEG | Atomic rename and durable file/directory writes required |
| Browser profile | IndexedDB (identity); localStorage (session), scoped to the origin | Identity-storage failure blocks entry |
