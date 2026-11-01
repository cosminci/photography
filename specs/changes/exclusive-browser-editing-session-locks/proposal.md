# Exclusive browser editing session locks

**Classification:** feature

**Evidence:** Fresh main has unused editing-session values and lock tables, while ordinary writes accept no browser ownership. Read-only imported-app inspection and current browser consumers establish that spread selection reuses cached content and photo-inventory events do not propagate ordinary edits.

## What and why

Give one browser session exclusive editing ownership of a spread, including its photo captions and editable text, or the full Cover. Other browsers can view the saved target and its owner's alias until release or expiry permits editing.

Deliver other browsers' saved changes live, so cached content cannot silently become the basis of a new editing session. [Design](design.md) records the existing-owner evolution and agreed protection boundaries.

## Contracts

| Concern | Guarantee |
| --- | --- |
| Browser identity | One remembered, nonblank identification alias per browser profile; aliases are recognizable labels, not unique names or authenticated users. |
| Application tab | One application tab per browser profile; additional tabs show a blocking explanation and retry rather than opening another workspace or Reader. |
| Session | Remembered browser identity survives reload; live backend sessions expire 30 seconds after their last successful heartbeat. |
| Target | One session owns at most one spread or full Cover; a grant expires after 30 seconds without accepted renewal, with heartbeat attempts every 10 seconds and no forced takeover. |
| Lifetimes | Releasing a target leaves its session alive; session heartbeat without a valid target grant cannot renew that target. |
| Navigation | Selecting another spread or Cover releases the previous target and attempts acquisition of the destination; a busy destination remains view-only. |
| Reader | Entering Reader or leaving the album releases target ownership while keeping viewing available. |
| Full protection | Content and destructive changes check current affected targets, regardless of their HTTP route or UI entry point; another session's affected lock rejects the entire mutation. |
| Explicit exceptions | Author selection and nondestructive insertion/reordering remain independently writable; ordering retains stable target identities and existing stale-order rejection. |
| Admission | Session, exact grant, current affected relationships and mutation share one coordination interval; a live-admitted atomic write can finish before a waiting acquisition proceeds. |
| Fencing | Expired, released or superseded grants cannot write, renew, release newer ownership or revive an obsolete acquisition. |
| Connectivity | Unreachable backend or unconfirmed ownership immediately blocks editing; attempted heartbeats do not extend leases. |
| Recovery | Lost acknowledgements require authoritative reconciliation; retained drafts never replay automatically after loss or reacquisition. |
| Live views | Saved-edit and ownership notifications invalidate authoritative resources; reconnect reconciles fresh content and ownership before editing resumes. |
| Restart | The single backend keeps volatile session authority; restart preserves saved albums but invalidates every previous session and target grant. |

Locking is access coordination, not a saved-book snapshot or an undo implementation. Shared content retains its domain owner even when an affected target's lease protects its mutation.

## Model evolution

The linked [reviewable design decision](design.md#model-evolution) owns concrete domain, operation and port changes, their migrated consumers, fencing and the protection map. Existing SQLite session/lock representations remain dormant rather than competing with the selected in-memory authority.

## Acceptance

- Independent browsers competing for one spread or full Cover obtain exactly one live owner.
- A contender can view saved content and Reader, sees the owner's alias, and cannot edit the locked target through any protected endpoint.
- One remembered alias identifies the browser across reloads and backend restarts without another identification prompt.
- A second tab in the same browser profile is blocked from the entire application until the previous active-tab binding releases or expires.
- A reload resumes a still-live session and target grant without allowing a duplicated tab to share editing access.
- Selection releases the previous target and acquires the new spread or Cover, or reports that destination as view-only.
- Reader and album departure release target ownership after pending writes and drafts are visibly resolved.
- A target expires after 30 seconds without accepted renewal, allowing another session to acquire it.
- Late writes, heartbeats, releases and acquisition retries cannot change newer ownership or saved content.
- Delayed requests and stream cleanup from an obsolete tab connection cannot revoke or use its successor's access.
- Backend unreachability blocks editing immediately and preserves unsaved text and photo adjustments.
- Ownership loss retains drafts separately from saved state and requires explicit review before another save.
- Failed or lost acquisition/release acknowledgements show uncertainty until authoritative reads resolve ownership.
- Protected caption, owner-text, template, deletion and source-change requests reject another session's affected lock without partial mutation.
- Nondestructive spread insertion/reordering and author changes remain available and propagate without invalidating target identity.
- Concurrent structural changes and acquisition cannot bypass current-target protection.
- Background photo transactions affecting another session's locked target retain saved data and report blocked processing, while unrelated photos can progress.
- Live saved changes update other browsers' authoritative text, composition, inventories, attribution and ordering without silently discarding drafts.
- Acquisition and reconnect reconcile current saved content before enabling editing, including changes missed while disconnected.
- Restart rejects old session/grant credentials; the browser remembers its alias, retains drafts and obtains new ownership before editing.
- Pending, locked, lost, reconnecting and retry states have accessible explanations and preserve keyboard/focus continuity at narrow widths.
- A continuously held grant can support a later history feature; released or lost ownership invalidates that later history permanently.

## Doc Sync

| Owner | Enduring change |
| --- | --- |
| `specs/design.md`, editing journey and architecture | Exclusive full-target ownership, single-tab identity, independently owned protected content, live authoritative reconciliation and draft-loss recovery. |
| `specs/http-api.openapi.json` | Session, grant, alias and ownership-event contracts; protected-write credentials/errors, authors/order exceptions and saved-edit invalidations. |
| `specs/testing.md`, confidence model | Fenced ownership, real mutation/SQLite races, independent-browser identity and disconnected/restart recovery. |
| `specs/operational.md`, topology and recovery | Volatile single-backend authority, heartbeat expiry, browser compatibility, immediate connectivity blocking and restart ownership loss. |

## Relationship to finishing

Locks are a separate prerequisite for reassessing browser-local undo; finishing's undo scope remains unresolved and no exclusion is approved. Presentation improvements remain with that feature; validated InDesign export is final and layout authoring remains deferred.
