# Photography operations

> Standard: Agentic Engineering Standards v1.2.0

## Run locally

Install the pinned toolchain as described in [Contributing](../CONTRIBUTING.md), then run from the repository root:

```sh
python3 scripts/local.py
```

The launcher builds frontend assets and starts the backend plus development server, reporting their URLs after readiness. Defaults are `127.0.0.1:8080` and `127.0.0.1:5173`; local SQLite state lives under `.local/`.

For isolated work, set `PHOTOGRAPHY_SQLITE_PATH`, `PHOTOGRAPHY_PHOTO_ROOT` and `PHOTOGRAPHY_THUMBNAIL_ROOT` to disposable development locations. The launcher overrides the database default only; explicitly choose a writable thumbnail directory. `PHOTOGRAPHY_PORT` and `PHOTOGRAPHY_FRONTEND_PORT` allow separate verification ports.

Stop with Ctrl+C. If a forked process survives, identify the listener and terminate that exact owned PID; preserve unrelated services and retained data.

## Frontend configuration

`VITE_BOOK_HISTORY_LIMIT` sets the number of completed book edits retained in browser-local history, defaulting to 10. Set it before starting the development server or building frontend assets; changing it requires a restart or rebuild, not a backend configuration change.

For example, `VITE_BOOK_HISTORY_LIMIT=25 npm --prefix frontend run build` retains 25 actions in that build. The [frontend configuration owner](../frontend/src/app/AppConfig.ts) rejects nonpositive, fractional or unsafe values explicitly.

## Run on the NAS

Use one backend instance with durable SQLite and thumbnail storage, and mount original photo directories read-only. The backend serves the built application and API together; the frontend development server is unnecessary on the NAS.

The repository currently supports source-based JVM startup, not a packaged NAS service or deployment manifest. With the pinned toolchain installed and `/data`, `/photos` and `/thumbnails` mapped to the intended NAS locations, build and run from the repository root:

```sh
npm ci --prefix frontend
npm --prefix frontend run build
cd backend
PHOTOGRAPHY_HOST=0.0.0.0 \
PHOTOGRAPHY_SQLITE_PATH=/data/photography.db \
PHOTOGRAPHY_PHOTO_ROOT=/photos \
PHOTOGRAPHY_THUMBNAIL_ROOT=/thumbnails \
sbt -batch run
```

Run under a NAS process supervisor that captures output and restarts failed processes. Keep the listener on a trusted network or behind an authenticated reverse proxy; the application does not provide user authentication.

| Storage | Ownership and operating requirement |
| --- | --- |
| SQLite | Writable, persistent application state; run only one backend against it. |
| Managed thumbnails | Writable derived media, backed up consistently with database references. |
| Original photographs | Independently owned, read-only source material; saved chapter paths must be visible to the backend. |

[Application configuration](../backend/src/main/resources/application.conf) owns environment settings and defaults. Set `PHOTOGRAPHY_HOST`/`PHOTOGRAPHY_PORT` for the listener and `PHOTOGRAPHY_THUMBNAIL_QUALITY` for preview quality; invalid configuration stops startup.

Trusted-network HTTP supports browser editing coordination. Preserve private headers and unbuffered, uncached Server-Sent Events through proxies; transport keepalives detect disconnected tabs without renewing editing leases.

Preserve immutable thumbnail caching independently of API and event responses. [OpenAPI](http-api.openapi.json) owns credential, lease and transport details.

## Editing access and recovery

The single backend keeps session and target authority in memory; existing SQLite session/lock tables remain dormant. Restart invalidates old credentials and grants while preserving saved albums and remembered browser identity and alias.

Accepted session heartbeats and target renewals extend separate leases. Expiry releases abandoned access after a crash or network loss; a failed heartbeat attempt extends nothing.

For a blocked extra tab, close the active tab and allow automatic binding retry; an unresponsive browser releases access on expiry. Remembered browser identity is separate from the temporary connection and should be retained.

When access becomes unconfirmed, reconnect and reconcile saved state before resubmitting retained drafts. Backend loss blocks the application; after restart or grant loss, review drafts explicitly because reacquisition never replays them.

## Readiness, logs and metrics

Probe `GET /health` for HTTP readiness. Startup migrates SQLite and reconciles unfinished photo writes before opening the listener. This probe establishes startup completion and reachability, not continuous database or storage health.

The backend writes startup and failure diagnostics through SLF4J to process output. Capture stdout/stderr with the local terminal or NAS supervisor; retain application errors alongside migration and database-pool diagnostics when investigating failures.

Current observability comprises the health endpoint, logs and chapter inventory/processing feedback. Dedicated metrics export, dashboards and alert rules are not configured. Monitor process availability, health responses and free space for the database and thumbnail volume at the host level; these are operating recommendations, not existing application metrics.

## Recovery and maintenance

Take a consistent backup of SQLite and its referenced thumbnail storage, preferably while the backend is stopped, and restore them together. Retain the independent originals backup. Preserve failed startup data and logs before changing files or retrying recovery.

After restart, unfinished thumbnail publication/cleanup is reconciled from durable intents. Source enumeration is explicit: use Refresh to resume interrupted photo processing. A failed scheduling attempt does not undo a saved chapter.

For checksum-incompatible disposable development databases, select a fresh database path or recreate only the explicitly identified disposable database. Retained spike data, NAS state and original photographs require their owner's authorization before replacement.
