# Photography contracts

## Provided

| Boundary | Definition |
| --- | --- |
| HTTP API, editing credentials, events and media | [OpenAPI](http-api.openapi.json) |
| Readiness | [Health route](../backend/src/main/scala/photography/adapters/http/HealthRoutes.scala) |
| InDesign export | [JSON Schema](../backend/src/main/resources/indesign-book.schema.json) and [import scripts](../scripts/indesign/) |

## Consumed

| Dependency | Required behavior and definition |
| --- | --- |
| SQLite/JDBC | Transactions, foreign keys and migrations: [connection](../backend/src/main/scala/photography/adapters/sqlite/SqliteConnection.scala), [migrator](../backend/src/main/scala/photography/adapters/sqlite/FlywayMigrator.scala). |
| Original-photo filesystem | Discovery and stable reads; I/O failures differ from unusable images: [storage port](../backend/src/main/scala/photography/ports/photo/PhotoStorage.scala). |
| Thumbnail filesystem | Atomic publication and durability synchronization; unsupported operations fail: [storage port](../backend/src/main/scala/photography/ports/photo/ThumbnailStorage.scala). |
| JPEG/EXIF and image codecs | Decoding, metadata and resampling: [source adapter](../backend/src/main/scala/photography/adapters/filesystem/FilePhotoStorage.scala), [generator](../backend/src/main/scala/photography/adapters/imageio/ImageIoPhotoThumbnailGenerator.scala). |
| Browser | IndexedDB, Web Storage, Fetch and EventSource: [composition](../frontend/src/main.tsx). |
| Runtime/build configuration | Runtime settings validated before startup; frontend settings fixed at asset build: [backend](../backend/src/main/resources/application.conf), [frontend](../frontend/src/app/AppConfig.ts). |

[Migrations](../backend/src/main/resources/db/) own persisted representations; [domain types](../backend/src/main/scala/photography/domain/) own validity. Dependency manifests own versions.
