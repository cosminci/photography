# Editing-session design decision

## Existing model

`EditorSession` relates `SessionId` to `EditorId` and a last heartbeat; `SessionLease` is a positive value with no consumer-defined unit. `EditingTarget` already distinguishes an interior `SpreadId` from an album's full Cover, and `EditingLock` associates a target with a session.

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
| `EditingTarget` | Reuse Spread and full Cover without another session-target model. | Cover's two sides form one target; target membership is read from existing saved owners. | Session acquisition, SpreadManager, CoverManager and content protection. |
| `EditingLock` | Add unique acquisition identity and independently renewable deadline. | Every new acquisition gets a different grant; SessionId alone never authorizes a protected target write. | Mutation credentials, heartbeat/release requests and browser ownership state. |
| `EditorSessionState`, `EditorTabState` | Evolve into authoritative live transitions and explicit active, blocked, stale and failed results; remove takeover semantics. | At most one target per live session and one owner per target; each target transition has a session-scoped fencing revision. | New `EditorSessionManager` operations and the runtime's single state instance. |
| `EditorSessionManager` | New behavior for the existing state owner: register/resume, read ownership, heartbeat, switch/acquire and release; expose checked mutation authorization. | Lifecycle transitions acquire the shared Mutex; mutation owners authorize inside their existing interval, never before it or through a second independent guard. | HTTP session boundary, existing mutation managers and ownership-event publication. |
| `AlbumCompositionRepository`, `PhotoPlacementRepository`, chapter/gear repositories | Evolve reads to retain affected target identities rather than only membership/used booleans. | Derive protection from current authoritative relationships; missing or failed acquisition is explicit and cannot become an empty affected set. | Content, structural and background mutation owners; SQLite adapters. |
| Spread/Cover placement and lifecycle operations | Accept live session and exact target-grant evidence; template replacement and deletion use the same guard. | Preserve collection checks and atomic writes; confirmation never overrides ownership. | PhotoPlacementApi, AlbumSpreadApi, HTTP clients, placement/structural browser consumers. |
| Album, chapter, photo-caption and gear operations | Check their actual content dependencies against current locks in the same interval as writing. | Keep each resource's domain owner; authors and nondestructive order/insertion are explicit exceptions, not permission to bypass destructive checks. | Existing content APIs, managers, repository adapters and content editors. |
| `ChapterPhotoImport`, `PhotoManager` | Guard affected publication/removal transactions; retain initiating grant evidence when work can affect its owner's target. | Background work cannot impersonate a newer grant; another owner's affected lock preserves saved data and produces processing feedback. | Import requests, transaction guard, photo repositories and inventory events. |
| `AlbumPhotoEvents` | Evolve the existing invalidation owner into `AlbumEvents`, with committed content/composition and ownership events plus session/global subscriptions. | Notifications follow commits or authority transitions and carry resource identity, not a second saved-state projection; bounded coalescing and failed-delivery feedback remain explicit. | Existing photo-event consumers, session SSE boundary, LiveWorkspace, catalog/Cover and gear consumers. |
| Browser session/ownership consumer | Retain browser identity, alias and resumable credential, then establish an exclusive backend-bound tab connection before exposing application routes. | Duplicate tabs have different document identities and cannot share active access; browser identity alone never authorizes a tab. | Application entrypoint, editor, catalog and Reader. |
| `PlacementSession`, `ContentSave`, workspace selection | Add grant-aware availability, retained loss drafts, acquisition reconciliation and explicit resubmission. | A draft belongs to its target and acquisition; later ownership never legitimizes automatic replay or older history. | Text/caption/photo controls, navigation, recovery and later history integration. |

`EditorSessionManager` is a lifecycle responsibility missing from the existing static state, not a parallel domain model or authentication service. Its authorization result is consumed by the manager that owns the mutation and already coordinates the current read/decision/write.

## Protection map

| Mutation | Affected target authority |
| --- | --- |
| Interior placements, transforms, swaps, caption corners and template replacement | The current spread; require its exact grant even when no competing lock exists. |
| Cover placements, transforms and side swaps | Full Cover; require its exact grant. |
| Photo caption | The photo's current placement target, including writes through the chapter/photo endpoint; an unplaced photo has no spread target. |
| Album synopsis | Album opening. |
| Album title | Album opening and Cover. |
| Author selection | Independently writable by explicit maintainer exception; notify other browsers of saved credits. |
| Chapter description and gear selection | The chapter opening whose content changes. |
| Referenced camera/lens catalog edits | Chapter openings whose rendered gear changes; preserve existing compatibility and used-entry checks. |
| Chapter metadata/source replacement | Targets whose owner text, source/placement references or identity changes, including Synopsis/Cover use of retired photos. |
| Spread deletion | The deleted spread; other-session ownership rejects deletion even with placement confirmation. |
| Album deletion | All contained spread/Cover targets; acquisition and deletion share coordination. |
| Nondestructive chapter/spread insertion or order changes | Remain independent of locks while preserving target identity/content; use existing capacity, membership and stale-order checks. |
| Background photo publication/removal | Current targets referencing the changed photo; guard each real transaction and retain existing per-photo progress semantics. |

Direct target edits require ownership; shared-resource writes require a live session and prove any affected lock they own, rejecting any affected other-session lock. Shared operations need not acquire every currently unlocked target, because checking those targets and committing is serialized with competing acquisition.

The protection map concerns authored content and destructive effects, not freezing navigation positions or independently permitted author credits. Source work and image decoding stay outside the critical section; affected-target validation returns inside it before each commit.

## Identity and transport

The browser stores its identity, alias and resumable credential per origin/profile; each loaded document creates a fresh, memory-only tab identity. Its live session SSE connection claims application access atomically before opening routes.

The backend blocks a different tab while the existing binding is live, even if both know the remembered session credential. Closing the first permits retry; reload resumes only after the previous binding is released or expires, without forced takeover.

Reconnecting the same document advances its connection generation; stream finalizers and delayed requests can affect only their exact binding. Session/target leases outlive stream closure until expiry, permitting safe reload without granting obsolete requests access.

HTTP owns registration/resume, ownership reads, heartbeats and target transitions; session SSE binds the active tab and delivers saved-edit/ownership invalidations. Public ownership exposes alias, opaque owner identity and remaining lease, never private session or tab credentials.

Credential-bearing SSE uses a request header rather than a URL, migrating the existing subscription consumer as needed. This retains trusted-network HTTP support and requires neither WebSockets, browser Web Locks nor new authentication infrastructure.

The existing event owner gains a session/global subscription for the active tab, covering catalog and selected-album invalidations. Consumers reuse authoritative resource reads and bounded coalescing, filtering by current view and reconciling after interrupted or missed delivery.

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

A write admitted live can complete atomically across expiry; competing acquisition waits and observes its committed result. Expired sessions, target grants and replaced tab bindings reject later admissions.

Failed heartbeats, ownership reads or backend connection immediately suspend editing. Reconnect establishes the live event subscription and reconciles authoritative content plus ownership; a still-live grant may resume, but an expired/restarted one requires fresh acquisition.

## Drafts and live reconciliation

Normal navigation resolves in-flight writes and visibly handles unsaved drafts before changing targets. Ownership loss stops queued writes and preserves text/transform drafts by target without draining them during retry, acquisition or reconnect.

Reconciliation reads saved state without writing; acknowledged commits retry reads only, and uncertain writes are compared with authoritative resources. A retained draft that differs from current saved content requires explicit review and submission under a new valid grant, never automatic overwrite.

Saved-resource events update the existing workspace acquisition owners, including text, gear, attribution, ordering, geometry and inventories. Acknowledged results take precedence over older reads; live refresh cannot discard pending previews or conceal unsaved conflicts.

Reader remains saved-state-only and receives live changes without target ownership. Lock status, owner alias, uncertainty, connection loss and retry remain accessible without stealing focus or collapsing controls; extra-tab blocking has its own persistent retry surface.

## Approval boundary

This decision and proposal require maintainer design/spec approval and an authorized initial spec merge before implementation. Concurrency delivery additionally requires independent review; direct work without subagents does not waive that gate.
