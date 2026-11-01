# Photography design

> Standard: Agentic Engineering Standards v1.2.0

## Purpose and system boundary

Album Layout Studio turns a year's chapter-organized photographs into a persisted album composition. The browser combines a photo collection, a spread editor and a distraction-free Reader; one backend serves the application and owns saved state.

```mermaid
flowchart LR
  Editor --> Browser["Album catalog and workspace"]
  Browser <-->|Commands and reads| Backend["Application services"]
  Backend --> Database[("Albums and composition")]
  Originals["Original photographs"] -->|Read only| Backend
  Backend --> Thumbnails[("Managed thumbnails")]
  Backend -->|Live inventory invalidations| Browser
```

Original photographs remain independently owned. The application stores editorial decisions and derived previews, rather than becoming the authoritative photo archive.

## Album model

```mermaid
flowchart TD
  Album --> Synopsis["Album opening: Synopsis"]
  Album --> Cover
  Album --> Chapters["Chronological chapters"]
  Chapters --> Opening["Chapter opening"]
  Chapters --> Spreads["Ordered photo spreads"]
  Chapters --> Photos["Chapter photo collection"]
  Synopsis & Opening & Spreads --> Template["Context-specific layout"]
  Synopsis & Opening & Spreads & Cover --> Placements
  Placements --> Photos
```

An album has a unique, immutable year and an editable title. Chapters describe destinations and visit periods; Home represents the whole year and remains last. The interior starts with Synopsis, followed by chapter blocks whose openings stay ahead of their photo spreads.

Editorial text is stored as plain text exactly as written and is separate from structured chapter data, so headings, dates and gear lines derive from structured fields rather than from parsing a description. The album owns its synopsis and a nonempty set of authors from Cosmin, Mara and Iacob; that attribution determines the Cover credits. Each chapter owns its description and gear selection. Each photo owns its caption independently of placement, while each interior placement owns only the caption's corner.

Cameras and lenses are shared catalogs edited independently of any chapter. A chapter's gear selection pairs each camera with lenses: fixed-lens cameras use none, and each interchangeable body uses at least one lens, which may serve several bodies. A lens fits a body when both share a mount and the body's sensor accepts its coverage, so full-frame bodies require full-frame lenses. Removing a camera does not remove its lenses, and catalog changes or deletions that would invalidate a saved selection are rejected.

Composition is bounded by the physical book: at most 65 interior spreads, excluding Cover. Templates provide layout geometry for their context; reference catalogs remain data, not duplicated application policy.

Photos belong to chapter collections independently of their use in layouts. Chapter spreads use their chapter's photos; Synopsis and Cover can use the album collection. Removing a spread releases its placements rather than deleting its photographs.

## Editing journey

```mermaid
stateDiagram-v2
  [*] --> Catalog
  Catalog --> Workspace: Create or open album
  Workspace --> Workspace: Edit structure and photo composition
  Workspace --> Reader: Enter Reader
  Reader --> Workspace: Resume editing
  Workspace --> Catalog: Return to catalog
```

The workspace keeps the selected part addressable and restores it across navigation and reloads. Structural editing operates within chapter boundaries; template choices follow the selected spread's context. Photo editing persists placements and transforms for interior layouts and Cover; collection availability is derived from saved placements. Reader presents the same saved composition without editing chrome.

```mermaid
sequenceDiagram
  actor Editor
  participant Workspace
  participant Application
  participant Storage
  Editor->>Workspace: Edit composition
  opt Adjust a photograph
    Workspace-->>Editor: Preview locally
    Editor->>Workspace: Complete adjustment
  end
  Workspace->>Application: Request mutation
  opt Existing placements need confirmation
    Application-->>Workspace: Report occupied spread
    Workspace-->>Editor: Explain impact and request confirmation
    Editor->>Workspace: Confirm
    Workspace->>Application: Repeat with confirmation
  end
  Application->>Storage: Commit validated change atomically
  Application-->>Workspace: Saved result
  opt Collection or structure changed
    Workspace->>Application: Reload affected authoritative views
  end
```

Local previews stay responsive while completed adjustments save in order. Navigation resolves pending adjustments before leaving; failed saves retain an explicitly unsaved preview for recovery.

Cancellation preserves saved state. Validation and confirmation precede writes; a failed refresh after a successful save retries the read, not the mutation. An uncertain write outcome is reconciled from authoritative state before another attempt. Accessibility, focus continuity and recoverable feedback apply throughout the journey.

Each text, attribution, caption or corner change and each complete gear replacement commits as one change. A chapter's metadata and gear are separate commits, so after a partial chapter save recovery retries only the unsaved part.

## Photo processing and live views

Chapter saves commit independently of background photo processing. Creation, a changed source folder or an explicit Refresh initiates reconciliation; opening an album reads saved state.

```mermaid
sequenceDiagram
  participant Editor
  participant Application
  participant Worker
  participant Originals
  participant Storage
  participant Browser
  Editor->>Application: Save chapter or request Refresh
  opt Saving chapter details
    Application->>Storage: Commit chapter
  end
  Application->>Worker: Schedule chapter reconciliation
  par Report scheduling outcome
    Application-->>Editor: Saved or scheduled result
  and Reconcile in background
    Worker->>Originals: Read current source photographs
    loop Each complete photo
      Worker->>Storage: Publish thumbnail and metadata safely
      Worker-->>Browser: Invalidate chapter inventory
      Browser->>Application: Read current inventory
    end
  end
```

Complete photos become visible progressively. Rediscovery keeps authored captions with their photos. Existing usable data remains available during replacement or recoverable processing failure. Durable write intents bridge database and filesystem updates so restart recovery can finish interrupted publication and cleanup.

The browser treats live events as invalidations, not a second database. Initial loads and reconnects assemble fresh album metadata, ordered chapters, composition membership, individual spreads and chapter photo inventories from authoritative resource reads. Independently acquired resources must agree on identities, chapter blocks and opening order before becoming the displayed workspace; incoherent acquisition is retried at most twice, with explicit failure and the last usable view retained if reconciliation fails. Country and template catalogs are independent resources. Ordinary composition changes become visible to other workspaces on reload.

Each saved spread supplies its geometry and placements together for editing and Reader. Navigation reuses the acquired resource; authoritative refreshes reconcile saved state without discarding pending adjustments or uncertain-write feedback. Cover is acquired when selected and refreshed on reconnect, while in-flight reads and unresolved saves retain their owning recovery lifecycle.

## Architectural decisions

Domain and application policy stay independent of HTTP, SQLite and image/filesystem adapters, following [Design principles](../DESIGN-PRINCIPLES.md). The composition root supplies concrete infrastructure.

The supported topology is one backend instance. Shared mutation coordination protects read/decision/write decisions, including gear replacement and catalog changes that affect saved selections; database transactions make supplied changes atomic. Slow source and image work runs outside that critical section.

Browser photo editing mutates placement resources; the interior composition owner also decides observed-order replacement, preserving the opening and membership atomically. Lightweight album, chapter and membership reads use their existing persistence authorities and representation validation, while atomic business workflows retain aggregate acquisition. Placement, structural editing and photo reconciliation share mutation coordination, preserving collection ownership and exclusive photo use across interior and Cover. The runtime owns resource acquisition and release, with recovery completed before serving. [Contracts](contracts.md) identify authoritative representations; [operations](operational.md) owns deployment and recovery.
