# Photography contracts

## Provided contracts

| Surface | Authoritative source |
| --- | --- |
| Browser/API exchange, editing credentials, events, caching and media delivery | [OpenAPI](http-api.openapi.json) |
| HTTP readiness | [Health route](../backend/src/main/scala/photography/adapters/http/HealthRoutes.scala) |
| InDesign handoff | [Export JSON Schema](../backend/src/main/resources/indesign-book.schema.json) and [import scripts](../scripts/indesign/) |

## Consumed contracts

| External dependency | Required contract | Authoritative definitions |
| --- | --- | --- |
| SQLite through JDBC | Transactions, foreign-key enforcement and migration compatibility for local application state. | [SQLite transactions](https://www.sqlite.org/lang_transaction.html), [foreign keys](https://www.sqlite.org/foreignkeys.html), [connection adapter](../backend/src/main/scala/photography/adapters/sqlite/SqliteConnection.scala), [migration adapter](../backend/src/main/scala/photography/adapters/sqlite/FlywayMigrator.scala) |
| Original-photo filesystem | Directory discovery and stable source reads; source access failures remain distinct from unusable image content. | [Photo-storage port](../backend/src/main/scala/photography/ports/photo/PhotoStorage.scala), [filesystem adapter](../backend/src/main/scala/photography/adapters/filesystem/FilePhotoStorage.scala) |
| Thumbnail filesystem | Atomic publication and durability synchronization; unsupported filesystem operations fail publication. | [Thumbnail-storage port](../backend/src/main/scala/photography/ports/photo/ThumbnailStorage.scala), [filesystem adapter](../backend/src/main/scala/photography/adapters/filesystem/FileThumbnailStorage.scala) |
| JPEG/EXIF decoding and image encoding | Available Java ImageIO codecs, metadata extraction and resampling for managed previews. | [Java ImageIO](https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/javax/imageio/ImageIO.html), [source decoder](../backend/src/main/scala/photography/adapters/filesystem/FilePhotoStorage.scala), [thumbnail generator](../backend/src/main/scala/photography/adapters/imageio/ImageIoPhotoThumbnailGenerator.scala) |
| Browser platform | IndexedDB and Web Storage for browser-local state; Fetch and EventSource for server exchange. | [IndexedDB](https://www.w3.org/TR/IndexedDB/), [Web Storage](https://html.spec.whatwg.org/multipage/webstorage.html), [Fetch](https://fetch.spec.whatwg.org/), [EventSource](https://html.spec.whatwg.org/multipage/server-sent-events.html), [browser composition](../frontend/src/main.tsx) |
| Host and asset-build configuration | Configuration parsing and validated settings supplied by the runtime or build environment. | [Application settings](../backend/src/main/resources/application.conf), [frontend configuration](../frontend/src/app/AppConfig.ts) |

Dependency versions belong to the [backend manifest](../backend/build.sbt) and [frontend manifest](../frontend/package.json). [Operations](operational.md) owns deployment and operating procedures for these dependencies.

## Internal definitions

| Definition | Authority |
| --- | --- |
| Domain validity and shared catalog rules | [Domain model](../backend/src/main/scala/photography/domain/) |
| Persistence representation and seeded reference data | [SQLite migrations](../backend/src/main/resources/db/) |
| Application dependency interfaces | [Ports](../backend/src/main/scala/photography/ports/) |

[Design](design.md) owns content relationships and application lifecycles.
