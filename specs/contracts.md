# Photography contracts

> Standard: Agentic Engineering Standards v1.2.0

## Contract inventory

| Surface | Authoritative source |
| --- | --- |
| Browser-facing API, events and thumbnail delivery | [OpenAPI](http-api.openapi.json) owns paths, representations, status codes and transport policies. |
| Saved-book print handoff | The [packaged JSON Schema](../backend/src/main/resources/indesign-book.schema.json) owns the representation shared by the export response and both InDesign importers; [import instructions](../scripts/indesign/README.md) own operator setup and use. |
| Persisted book content, composition and shared catalogs | [SQLite migrations](../backend/src/main/resources/db/) own relational structure and seeded identities, names and geometry. |
| Application dependencies | [Ports](../backend/src/main/scala/photography/ports/) define the boundaries implemented by infrastructure adapters. |

## Boundary semantics

The backend delivers the built browser application and API on one origin. Application navigation resolves through the frontend entry point; API and managed-media routes retain their own contracts.
