# Photography design

## Backend responsibilities

```mermaid
flowchart TB
  Client["Browser presentation / API clients"] --> HTTP["HTTP adapters"]
  subgraph Backend["Backend"]
    subgraph Core["Domain and application"]
      Book["Book usecases<br/>Lifecycle / content<br/>Interior / Cover<br/>Gear selection / catalogs<br/>Saved-book export"]
      Access["Editing admission"]
      Photos["Photo processing<br/>Import / reconciliation<br/>Write recovery"]
      Repositories["Repository ports"]
      OriginalsPort["Original-photo port"]
      GeneratorPort["Thumbnail-generation port"]
      ThumbnailsPort["Thumbnail-storage port"]
      Access -->|Protected commands| Book
      Book -->|Chapter sources| Photos
      Book & Photos --> Repositories
      Photos --> OriginalsPort & GeneratorPort & ThumbnailsPort
    end
    HTTP -->|Reads and independent commands| Book
    HTTP --> Access
    Repositories --> SQLite["SQLite adapters"]
    OriginalsPort --> Files["Original-file adapter"]
    GeneratorPort --> Codec["JPEG / EXIF / ImageIO adapter"]
    ThumbnailsPort --> Media["Managed-file adapter"]
  end
  SQLite --> State[("Saved book, catalogs and write intents")]
  Files --> Originals[("Independent original-photo archive")]
  Media --> Thumbnails[("Derived thumbnails")]
```

[External protocols and formats](contracts.md).

## Book model

```mermaid
flowchart TB
  Album["Album<br/>Year, title, synopsis, authors"] --> Opening["Synopsis opening"]
  Album --> Cover["Cover<br/>Front, back and spine owner"]
  Album --> Chapters["Ordered chapters<br/>Destination, period, description, source and gear selection"]
  Chapters --> ChapterOpening["Chapter opening"]
  Chapters --> Spreads["Ordered photo spreads"]
  Chapters --> Collection["Chapter photo collection<br/>Photo identity, source metadata and caption"]
  Opening & ChapterOpening & Spreads --> Interior["Interior placements<br/>Photo reference, transform and caption position"]
  Cover --> Exterior["Cover placements<br/>Photo reference and transform"]
  Interior & Exterior -->|Reference; do not own| Collection
  Templates["Context-specific template catalog"] -->|Geometry| Opening & ChapterOpening & Spreads
  Cameras["Shared camera catalog"] -->|Selected by identity| Chapters
  Lenses["Shared lens catalog"] -->|Selected by identity| Chapters
```

**Invariants**

- Album years are unique and immutable; author selections are nonempty and supply Cover credits.
- Home covers its album's whole year, remains last and cannot change into another chapter type; an album has at most one Home.
- A source folder belongs to at most one chapter in an album.
- The interior begins with Synopsis, followed by chapter blocks whose openings cannot be deleted or reordered behind photo spreads; it contains at most 65 spreads, excluding Cover.
- Chapter placements use that chapter's collection; Synopsis and Cover can use the album collection. A photo can occupy only one placement across interior and Cover.
- Removing a photo spread releases placements without deleting collection photos or photo-owned captions.
- Camera and lens lifecycles are independent; catalog edits must preserve every affected saved selection, and referenced entries cannot be deleted.

## Layout geometry

```mermaid
flowchart TD
  Count["Actual interior spread count"] --> Cover["Cover sizing"]
  Specifications["Saved print specifications"] --> Cover
  Spine["Saved spine owner"] --> Cover
  Cover --> Frames["Front/back frames; spine included in its owner's frame"]
  Bleed["Enable spread-wide bleed"] --> Full{"Full-height outer photo?"}
  Full -->|Yes| Three["Full-height photos bleed top, bottom and outward; opposite stack stays inset"]
  Full -->|No| Stacks["Outer stacks bleed outward together"]
  Three & Stacks --> Inner["Inner slots remain inset"]
```

**Invariants**

- Bleed changes edges, not template geometry or placement identities; mixed saved directions remain readable without automatic normalization.
- Cover sizing, editor and Reader use the same saved transforms and authoritative frames.

## Chapter planning and source changes

```mermaid
sequenceDiagram
  participant Caller
  participant Metadata as Chapter metadata usecase
  participant Planning as Chapter planning
  participant Saved as Repository ports<br/>SQLite
  participant Import as Import coordinator
  Caller->>Metadata: Create or update chapter
  Note over Metadata,Saved: Shared mutation coordination
  Metadata->>Saved: Read album year, chapters<br/>and interior count
  Metadata->>Planning: Validate metadata<br/>Plan chronological order<br/>and opening
  alt Invalid plan or unavailable state
    Metadata-->>Caller: Rejected / missing / failed<br/>No planned write
  else Valid plan
    Metadata->>Saved: Commit chapter and order
    opt Replacing source
      Note over Metadata,Saved: Same transaction:<br/>record retirements and remove old photo references
    end
    Saved-->>Metadata: Commit outcome
    opt Update acknowledgement fails
      Metadata->>Saved: Resolve against current<br/>metadata and order
    end
    break Unresolved source-change commit
      Metadata->>Import: Suspend chapter import
      Metadata-->>Caller: Failed
    end
    break Failed / missing commit
      Metadata-->>Caller: Failed / missing<br/>No new work launched
    end
    opt New or changed source
      Metadata->>Import: Prepare current<br/>source generation
    end
    Note over Metadata,Import: Launch after metadata coordination ends
    opt New or changed source
      Metadata->>Import: Clean retirements<br/>Attempt prepared work
    end
    Metadata-->>Caller: Created / updated<br/>+ scheduling outcome
  end
```

**Invariants**

- Scheduling failure does not undo a committed chapter. An unchanged source schedules no import; Refresh requests processing without rewriting chapter metadata.
- Chapter metadata and gear selection have separate commits; a partial save preserves the completed owner.
- Reordering compares the observed order with current membership before replacing it; stale observations cannot overwrite newer order.
- Template changes and deletion of occupied photo spreads require confirmation before releasing placements.

## Protected mutation admission

```mermaid
sequenceDiagram
  participant Caller
  participant Access as Editing admission
  participant Authority as Sessions and grants
  participant Owner as Owning usecase
  participant Saved as Repository / SQLite
  Caller->>Access: Target-bound command<br/>+ connection / grant
  Note over Access,Saved: Shared mutex spans admission / operation<br/>Every outcome releases it
  Access->>Authority: Validate live connection
  break Invalid connection
    Access-->>Caller: Reject<br/>Do not invoke mutation
  end
  opt Target follows current opening or placed photo
    Access->>Saved: Read current affected relationships
    break Missing relationship or read failure
      Access-->>Caller: Missing / failed<br/>Do not invoke mutation
    end
  end
  Access->>Authority: Check caller grant<br/>and conflicting ownership
  alt Stale grant or another owner
    Access-->>Caller: Reject<br/>Do not invoke mutation
  else Admitted
    Access->>Owner: Apply business operation
    Owner->>Saved: Commit validated<br/>owned change
    Saved-->>Owner: Result / commit uncertainty
    Owner-->>Access: Operation outcome
    Access-->>Caller: Outcome
  end
```

**Invariants**

- One browser profile binds one application tab; one session owns at most one interior spread or the full Cover. Other sessions retain saved-state reads and cannot force takeover.
- Session liveness, exact-generation tab binding and target grants have separate lifetimes. Unbinding does not end a live session/grant; target release does not end the session.
- Accepted heartbeats renew the session lease; accepted target renewals renew the grant lease. Rejected requests extend neither.
- Backend restart clears volatile authority, not saved books or remembered browser identity.
- Synopsis, chapter-opening description, placed-photo caption, composition, template change and spread deletion require their current target's authority. Album title, chapter name/countries/dates/source, gear/catalogs, authors and nondestructive insertion/reordering remain independent of target locks.
- Failed relationship acquisition is a failure, never an empty affected set. A write admitted while authority is live may finish across expiry; later acquisition observes its completion.
- Original-file reading and image generation stay outside the shared mutation interval.

## Photo processing

```mermaid
flowchart TD
  Request["New source or Refresh"] --> Active{"Same chapter/source already active?"}
  Active -->|Yes| Reuse["Reuse current request; no duplicate worker"]
  Active -->|No| Queue["Queue a new source generation"]
  Queue --> Permit["Acquire the single processing permit"]
  Permit --> Discover{"Original discovery complete?"}
  Discover -->|No| Failure["Report issues; retain saved inventory"]
  Discover -->|Yes| Reconcile["Read originals, generate previews<br/>and publish complete photos"]
  Reconcile --> Retire["Retire absent photos<br/>Record intent and remove references atomically"]
  Retire --> Finished["Finish with processing / cleanup outcomes"]
  Failure & Finished --> Release["Release acquired processing permit"]
  Reconcile & Retire -.-> Events["Invalidate chapter inventory"]
  Events --> Read["Consumers read authoritative inventory"]
  Superseded["Source superseded"] --> Fence["Fence old generation's database mutations; cancel old work"]
  Fence --> Release
```

**Invariants**

- Only the current source generation can publish a coordinated database mutation.
- Refresh preserves photo identity and authored caption when replacing that photo's metadata/thumbnail; individual read or generation failures retain the previous usable photo.
- An unchanged photo hash and selected thumbnail quality reuse the current thumbnail.

## Thumbnail publication and recovery

```mermaid
sequenceDiagram
  participant Photos as Photo<br/>reconciliation
  participant Saved as Photo repository<br/>SQLite
  participant Media as Thumbnail storage
  participant Recovery as Write recovery
  Photos->>Saved: Record publication intent<br/>before writing media
  alt Evidence cannot be recorded
    Saved-->>Photos: Fail without<br/>writing thumbnail
  else Durable intent exists
    Photos->>Media: Store thumbnail at fresh location
    alt Thumbnail stored
      Photos->>Saved: Publish photo metadata<br/>and new thumbnail reference
    else Storage failed
      Note over Photos,Saved: Retain old committed photo reference
    end
    Photos->>Recovery: Resolve intent after success or failure
    Recovery->>Saved: Read actual committed thumbnail references
    alt Reference read fails
      Recovery-->>Photos: Report issue and retain unfinished intent
    else References acquired
      Recovery->>Media: Remove unreferenced new<br/>and previous locations
      alt Cleanup fails
        Recovery-->>Photos: Report issue and retain unfinished intent
      else Cleanup succeeds
        Recovery->>Saved: Attempt intent completion<br/>Retain evidence on failure
      end
    end
  end
  Note over Saved,Recovery: Retirement uses the same reference-based cleanup
```

[Operator recovery](operational.md#recovery-and-maintenance).

## Saved-book export

```mermaid
sequenceDiagram
  participant Caller
  participant Export as Book export
  participant Saved as Saved owners
  participant Schema as Handoff schema
  participant InDesign
  Caller->>Export: Export album
  Export->>Saved: Read album/chapter metadata<br/>composition / Cover<br/>gear / photo records
  Saved-->>Export: Saved facts
  break Missing or incoherent owners / opening order
    Export-->>Caller: Not found / failed<br/>No partial handoff
  end
  Export->>Export: Keep referenced photos<br/>Check readiness<br/>Project saved content / frames
  break Invalid projection
    Export-->>Caller: Failed<br/>No partial handoff
  end
  alt Blocking issues
    Export-->>Caller: Located issues<br/>No artifact
  else No blocking issues
    Export->>Schema: Validate projected JSON<br/>including warnings
    alt Invalid schema
      Export-->>Caller: Failed<br/>No artifact
    else Valid schema
      Export-->>Caller: Ready JSON handoff
      Caller->>InDesign: Open handoff with local originals
      InDesign->>InDesign: Compose print output
    end
  end
```

[Handoff contract](contracts.md#provided-boundaries).

**Invariants**

- Empty Synopsis, chapter descriptions or photo slots block export; empty photo captions are valid.
- Effective resolution below 300 DPI is a warning, not a blocker.
- Export reads saved facts without reading original files and assumes editing has stopped; original availability and final print fidelity are checked in InDesign.

## Presentation at the backend boundary

```mermaid
flowchart LR
  Backend["Edits / Undo / Redo<br/>via existing backend commands"] --> Outcome{"Write outcome"}
  Outcome -->|Acknowledged| Read["Read affected saved state"]
  Read -->|Available| Views["Catalog, editor and Reader projections"]
  Read -->|Unavailable| Retry["Retry reads, not the mutation"]
  Outcome -->|Rejected| Draft["Retain recoverable draft"]
  Outcome -->|Uncertain| Reconcile["Reconcile before another write attempt"]
  Invalidations["Inventory / ownership invalidations"] --> Read
```

**Invariants**

- Older in-flight reads cannot replace acknowledged saved state. Ownership/reconnection events never grant authority or replay drafts automatically.
- Navigation and Reader retain browser history; album exit, reload or unexpected access loss ends it.
- Compensation preserves unrelated saved work and follows regenerated identities. Interrupted compensation retains progress; a blocked or uncertain newest action stops traversal.
- Chapter creation and source replacement establish history boundaries; shared-catalog changes remain outside book history.
