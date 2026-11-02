# Editing-session design decision

## Existing model

`EditorSession` relates `SessionId` to `EditorId` and a last heartbeat; `SessionLease` is a positive value with no consumer-defined unit. `EditingTarget` already distinguishes an interior `SpreadId` from an album's exterior cover spread, and `EditingLock` associates a target with a session.

`EditorSessionState` holds browser-owner sessions and locks, but has no runtime consumer. Its unused `EditorTabState.TakeoverRequired` conflicts with the approved no-takeover behavior.

The V1 SQLite representation already has session and spread/Cover lock tables. No adapter enforces their authority; the selected single-backend in-memory authority evolves the existing application state without using these dormant tables or changing applied migrations.

`SpreadManager`, `CoverManager`, chapter workflows and `ChapterPhotoImport` share one runtime Mutex. Placement writes use it for collection and identity checks, while album deletion and photo-caption saves currently bypass browser ownership.

`PlacementSession` sequences transforms but automatically drains retained adjustments after retry. `LiveWorkspace` reacquires on reconnect and otherwise reuses saved resources; existing events invalidate photo inventory, not ordinary editorial or composition changes.

## Model evolution

| Existing owner or contract | Proposed evolution | Required invariant and failure semantics | Migrated suppliers and consumers |
| --- | --- | --- | --- |
| `EditorId` | Remembered browser-profile identity, separate from the identification alias. | Identity survives reload/restart; a public alias does not confer editing authority. | Browser storage and session registration. |
| `SessionId`, `EditorSession` | Backend-issued private session credential, validated alias and live-session deadline. | One live session per browser identity; resume requires its retained credential, not merely an EditorId or alias. | Session HTTP boundary, tab lifecycle and existing application state. |
| `EditorSession` live-tab binding | Add a fresh document identity and backend-issued connection generation, distinct from remembered browser identity. | One active tab connection per browser session; a different live tab is blocked, and older connection credentials cannot authorize requests. | Session event-stream acquisition/release, heartbeat and every browser mutation. |
| `SessionLease` | Define seconds explicitly and enforce 30-second session and target leases with 10-second heartbeat guidance. | Successful session heartbeat and target renewal are separate transitions; elapsed-time expiry is backend-authoritative. | Composition supplies validated fixed lease policy; session responses supply timing to the browser. |
| `EditingTarget` | Reuse Spread and Cover without another session-target model. | The exterior spread's two sides form one target; target membership is read from existing saved owners. | Session acquisition, route declarations and existing content owners. |
| `EditingLock` | Add unique acquisition identity and independently renewable deadline. | Every new acquisition gets a different grant; SessionId alone never authorizes a protected target write. | Mutation credentials, heartbeat/release requests and browser ownership state. |
| `EditorSessionState`, `EditorTabState` | Evolve into authoritative live transitions and explicit active, blocked, stale and failed results; remove takeover semantics. | At most one target per live session and one owner per target; each target transition has a session-scoped fencing revision. | New `EditorSessionManager` operations and the runtime's single state instance. |
| `EditorSessionManager` | New behavior for the existing state owner: register/resume, read ownership, heartbeat, switch/acquire and release; expose checked mutation authorization. | Lifecycle transitions and the shared editing executor use the same Mutex; authorization and admitted mutation completion occupy one interval. | HTTP session boundary, `EditingAccess` and ownership-event publication. |
| `AlbumCompositionRepository`, `PhotoPlacementRepository` | Evolve reads to retain affected target identities rather than only membership/used booleans. | Derive protection from current authoritative relationships; missing or failed acquisition is explicit and cannot become an empty affected set. | Content and structural mutation owners; SQLite adapters. |
| Spread/Cover placement and lifecycle operations | Declare required ownership at their routes; centralized enforcement validates live connection and exact grant evidence before calling existing content owners. | Preserve collection checks and atomic writes; confirmation never overrides ownership. | PhotoPlacementApi, AlbumSpreadApi, shared executor, HTTP clients and placement/structural browser consumers. |
| Album synopsis and photo-caption operations | Declare their actual content dependencies for centralized protection in the same interval as writing. | Keep each resource's domain owner; authors and nondestructive order/insertion remain independently writable. | Policy-declaring routes, shared enforcement, repository adapters and content editors. |
| Chapter metadata, description, gear selection and shared gear catalogs | Retain existing operations without target-lock protection. | A spread grant does not freeze these independently editable resources. | Existing chapter and gear consumers. |
| `ChapterPhotoImport`, `PhotoManager` source/import behavior | Unchanged; source-folder changes and background import/refresh are outside target-lock protection. | Retain existing source-generation checks, transactions and inventory events. | Existing source and import consumers. |
| `AlbumPhotoEvents`, session ownership transport | Keep existing photo-inventory SSE; session SSE carries tab/ownership transitions without ordinary saved-edit streaming. | Ownership notifications never grant access; reconnect reconciles authoritative saved content and ownership. | Existing inventory consumers and session SSE lifecycle. |
| `LiveWorkspace`, workspace navigation | Reuse the full saved-workspace reload for spread/Cover navigation, acquisition and reconnect; read selected Cover placements and chapter gear through their existing owners. | Pending writes, drafts and uncertain recovery block departure; failed acquisition blocks editing and remains retryable; obsolete reads cannot replace newer acknowledged content. | Editor and Reader selection, browser history and future ownership acquisition. |
| Browser session/ownership consumer | Retain browser identity, alias and resumable credential, then establish an exclusive backend-bound tab connection before exposing application routes. | Duplicate tabs have different document identities and cannot share active access; browser identity alone never authorizes a tab. | Application entrypoint, editor, catalog and Reader. |
| `PlacementSession`, `ContentSave`, workspace selection | Add grant-aware availability, retained loss drafts, acquisition reconciliation and explicit resubmission. | A draft belongs to its target and acquisition; later ownership never legitimizes automatic replay or older history. | Text/caption/photo controls, navigation, recovery and later history integration. |

`EditorSessionManager` is a lifecycle responsibility missing from the existing static state, not a parallel domain model or authentication service. `EditingAccess` consumes its authorization result while existing business managers retain content validation and persistence.

## Protection map

| Mutation | Affected target authority |
| --- | --- |
| Interior placements, transforms, swaps, caption corners and template replacement | The current spread; require its exact grant even when no competing lock exists. |
| Cover placements, transforms and side swaps | The exterior spread; require its exact grant. |
| Photo caption | The spread containing the photo, including writes through the chapter/photo endpoint; an unplaced photo has no spread to protect. |
| Album synopsis | Album opening. |
| Author selection | Independently writable by explicit maintainer exception. |
| Chapter metadata, description, gear selection and shared gear catalogs | Independently writable without target-lock protection; preserve existing validation and compatibility checks. |
| Spread deletion | The deleted spread; other-session ownership rejects deletion even with placement confirmation. |
| Nondestructive chapter/spread insertion or order changes | Remain independent of locks while preserving target identity/content; use existing capacity, membership and stale-order checks. |

Direct target edits require ownership; shared-resource writes require a live session and prove any affected lock they own, rejecting any affected other-session lock. Shared operations need not acquire every currently unlocked target, because checking those targets and committing is serialized with competing acquisition.

The protection map concerns authored content and spread deletion, not freezing navigation positions or independently permitted author credits. Source-folder changes and background photo processing retain their existing behavior.

## Identity and transport

The browser allocates its profile identity atomically in IndexedDB, so simultaneous first-run tabs share one identity. It remembers the alias and resumable credential separately; each loaded document creates a fresh, memory-only tab identity.

Its live session SSE connection claims application access atomically before opening routes.

The backend blocks a different tab while the existing binding is live, even if both know the remembered session credential. Closing the first permits retry; reload resumes only after the previous binding is released or expires, without forced takeover.

Reconnecting the same document advances its connection generation; stream finalizers and delayed requests can affect only their exact binding. Session/target leases outlive stream closure until expiry, permitting safe reload without granting obsolete requests access.

HTTP owns registration/resume, ownership reads, heartbeats and target transitions; session SSE binds the active tab and delivers ownership invalidations. Public ownership exposes alias, opaque owner identity and remaining lease, never private session or tab credentials.

Credential-bearing SSE uses a request header rather than a URL, migrating the existing subscription consumer as needed. This retains trusted-network HTTP support and requires neither WebSockets, browser Web Locks nor new authentication infrastructure.

The session subscription retains active-tab and ownership updates; existing photo-inventory events remain unchanged. Consumers reuse authoritative resource reads on navigation, acquisition and reconnect rather than streaming ordinary saved edits.

Authority uses backend elapsed-time deadlines; displayed expiry is guidance rather than client authorization. At expiry, ownership is invalidated and announced even without a new contender; notifications alone never grant editing rights.

## Transitions and fencing

| Trigger | Backend decision | Browser result |
| --- | --- | --- |
| Register/resume | Create a session for an unowned/expired browser identity, or resume only with the live credential. | Remember alias and identity; stale credentials require a new session, not alias re-entry. |
| Bind tab stream | Claim an unbound session or reconnect its same document with a new connection generation; reject a different active tab. | Open the application only after binding confirmation; additional tabs have blocking feedback and retry. |
| Select target | Compare expected session target-transition revision, release the previous grant and acquire a free destination, or report its live owner. | Resolve pending work first; publish only reconciled destination content, with owned or view-only state. |
| Heartbeat | Renew a live session and only the supplied still-live exact target grant. | A stale-target result cannot renew or revive ownership; reconcile before further edits. |
| Release | Validate the supplied grant and transition revision; release only that ownership. | Reader/departure proceeds after outcome reconciliation; loss or uncertainty is never reported as confirmed release. |
| Lease expiry | Reject new admissions and remove expired ownership; never renew it from a late request. | Retain drafts, disable edits and offer explicit fresh acquisition. |
| Lost acknowledgement | An operation may have changed ownership or saved data; read current session/target/resource state. | Pending/uncertain state blocks edits; retry does not blindly repeat a transition or mutation. |
| Backend restart | New volatile authority recognizes none of the former sessions/grants. | Remembered identity/alias register anew; drafts remain separate from saved state and require review. |

Each target transition advances a session-scoped revision, including release and unsuccessful destination contention after release. This prevents a delayed acquisition from recreating an old target after a newer navigation cycle has released it.

Every browser mutation validates its current session and tab binding, including permitted author/order changes. Protected admissions additionally check the exact target grant and current affected relationships under the mutation's shared Mutex.

## Central route enforcement amendment

One active application tab per browser profile and protection of a spread from another browser's edits are separate rules; the cover is an exterior spread, with both sides protected together.

Each route declares its access requirement beside its handler through `Routes`, without a default policy or separate URL table; the route aggregator installs enforcement, while session lifecycle endpoints retain their own connection and lease transitions.

`EditingAccess` reuses the existing authority and shared coordination: request decoding precedes coordination, and current relationship reads, authorization and mutation completion share one interval with competing acquisition.

Ordinary business managers retain their content decisions and persistence outcomes without browser credentials or access-error variants; their previous nested coordination moves to the shared executor as each operation migrates.

Source-folder changes and background photo publication/removal are excluded from target-lock protection by the approved freshness amendment.

Application component tests own admission, current relationship resolution, fencing, expiry, cancellation and serialization with competing acquisition, using substituted repository ports. HTTP seams prove credential decoding, declared-policy forwarding, response encoding and stream acquisition/finalization through substituted application services. Database seams prove persisted representation, transaction atomicity, rollback and acknowledgement uncertainty through real storage, without recreating application locking scenarios.

The refactoring delivery migrates all protected operations together and requires explicit declarations for every API route. Chapter and gear operations retain their existing public declarations under the approved scope exclusion; browser ownership integration completes the remaining implementation.

Album title editing and album deletion are outside this feature's scope; their existing behavior is unchanged.

A write admitted live can complete atomically across expiry; competing acquisition waits and observes its committed result. Expired sessions, target grants and replaced tab bindings reject later admissions.

Failed heartbeats, ownership reads or backend connection immediately suspend editing. Reconnect establishes the live event subscription and reconciles authoritative content plus ownership; a still-live grant may resume, but an expired/restarted one requires fresh acquisition.

## Drafts and live reconciliation

Normal navigation resolves in-flight writes and visibly handles unsaved drafts before changing targets. Ownership loss stops queued writes and preserves text/transform drafts by target without draining them during retry, acquisition or reconnect.

Reconciliation reads saved state without writing; acknowledged commits retry reads only, and uncertain writes are compared with authoritative resources. A retained draft that differs from current saved content requires explicit review and submission under a new valid grant, never automatic overwrite.

Navigation, acquisition and reconnect reuse saved-workspace acquisition, including text, gear, attribution, ordering, geometry and inventories. Acknowledged results take precedence over older reads; reconciliation cannot discard pending previews or conceal unsaved conflicts.

Reader remains saved-state-only and reads current content when navigating, without target ownership or immediate propagation into an already-open view. Lock status, owner alias, uncertainty, connection loss and retry remain accessible without stealing focus or collapsing controls; extra-tab blocking has its own persistent retry surface.

## Approval boundary

This decision and proposal require maintainer design/spec approval and an authorized initial spec merge before implementation. Concurrency delivery additionally requires independent review; direct work without subagents does not waive that gate.
