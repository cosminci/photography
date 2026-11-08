# Photography contracts

## Provided boundaries

| Consumer | Authoritative definition and interpretation |
| --- | --- |
| Browser and API clients | [OpenAPI](http-api.openapi.json) owns paths, payloads, status codes, editor credentials, leases and event transport. Aliases/credentials coordinate editing; they are not authentication. |
| Browser navigation and static assets | [Static-site adapter](../backend/src/main/scala/photography/adapters/http/StaticSite.scala) serves the built app on the API origin; navigation fallback remains separate from API/media errors. |
| InDesign | [Export JSON Schema](../backend/src/main/resources/indesign-book.schema.json) owns the saved-book handoff. [Consumer scripts](../scripts/indesign/) resolve original-photo resources on the operator's machine; deployment thumbnail paths are not print resources. |

API successes and failures use `Cache-Control: no-store`; versioned thumbnails and static assets have separate caching policies. A proxy must preserve the OpenAPI editor-header and event-stream contract without applying API caching rules to immutable media.

## Consumed boundaries

| Dependency | Authoritative definition and interpretation |
| --- | --- |
| SQLite and Flyway | [Migrations](../backend/src/main/resources/db/) own persisted representation and seeded catalogs; [connection](../backend/src/main/scala/photography/adapters/sqlite/SqliteConnection.scala) enables foreign keys, and [migrator](../backend/src/main/scala/photography/adapters/sqlite/FlywayMigrator.scala) owns schema history. Runtime/state compatibility and recovery belong to [operations](operational.md#recovery-and-maintenance). |
| Original-photo filesystem | [PhotoStorage](../backend/src/main/scala/photography/ports/photo/PhotoStorage.scala) distinguishes discovery, unreadable/unstable sources and failed reads; [filesystem adapter](../backend/src/main/scala/photography/adapters/filesystem/FilePhotoStorage.scala) discovers JPEGs in the selected directory. Source paths refer to the backend-visible filesystem, not the browser's filesystem. |
| Managed-thumbnail filesystem | [ThumbnailStorage](../backend/src/main/scala/photography/ports/photo/ThumbnailStorage.scala) and [adapter](../backend/src/main/scala/photography/adapters/filesystem/FileThumbnailStorage.scala) own storage/version lookup. A missing thumbnail is distinct from successful empty media. |
| JPEG decoding, EXIF and encoding | [Generator port](../backend/src/main/scala/photography/ports/photo/PhotoThumbnailGenerator.scala) and [ImageIO adapter](../backend/src/main/scala/photography/adapters/imageio/ImageIoPhotoThumbnailGenerator.scala) define generated-preview outcomes. Unsupported/undecodable originals do not become valid photos through a default image. |
| Browser storage and event transport | [Browser identity](../frontend/src/features/editing/BrowserIdentity.ts) requires IndexedDB; [session owner](../frontend/src/features/editing/EditorSession.ts) uses origin-local storage, and [browser composition](../frontend/src/main.tsx) binds those capabilities and EventSource. Identification-storage failure blocks entry instead of silently assigning another identity. |
| Runtime configuration | [Application configuration](../backend/src/main/resources/application.conf) and [typed reader](../backend/src/main/scala/photography/app/config/AppConfig.scala) own backend settings and validation; [browser configuration](../frontend/src/app/AppConfig.ts) owns build-time history capacity. |

[Application ports](../backend/src/main/scala/photography/ports/) own the domain-valid contracts used by adapters, including persistence decisions; [design](design.md) owns the book and editing guarantees those contracts support. Dependency versions remain in the [backend build](../backend/build.sbt) and [frontend lockfile](../frontend/package-lock.json).
