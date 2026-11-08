# Photography contracts

> Standard: Agentic Engineering Standards v1.2.0

## Contract inventory

| Surface | Authoritative source |
| --- | --- |
| Browser-facing API, editing credentials, events and thumbnails | [OpenAPI](http-api.openapi.json): paths, representations, status codes, leases and transport policies. |
| Saved-book print handoff | [JSON Schema](../backend/src/main/resources/indesign-book.schema.json): export consumed by InDesign. |
| Persisted book content, composition and shared catalogs | [SQLite migrations](../backend/src/main/resources/db/): relational structure and seeded identities, names and geometry. |
| Domain validity, including gear selection | [Domain model](../backend/src/main/scala/photography/domain/): valid values and catalog-relative rules. |
| Application dependencies | [Ports](../backend/src/main/scala/photography/ports/): boundaries implemented by infrastructure adapters. |
| Runtime settings | [Application configuration](../backend/src/main/resources/application.conf); [frontend configuration](../frontend/src/app/AppConfig.ts). |

## Boundary semantics

The backend delivers the built browser application and API on one origin. Application navigation resolves through the frontend entry point; API and managed-media routes retain their own contracts.

API responses use `Cache-Control: no-store` for successful and failed operations. Managed thumbnails retain their versioned immutable caching, and static assets retain their independent delivery policy.

[Design](design.md) explains content ownership, mutation coordination and recovery semantics. [Operations](operational.md) explains network exposure and the stored-state recovery unit.
