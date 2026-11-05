# Validated InDesign export

**Classification:** feature

**Evidence:** the six printed albums already used one combined interior/cover JSON with separate InDesign documents. Their production files and the inspected 2025 documents establish existing output, not the exact generating script version; the current application has no export action.

## What and why

Export the complete saved book as one validated JSON, then use the existing InDesign scripts to populate separate Saal interior and cover documents. Preserve the established printed result while removing obsolete saved-session data and ambiguous file selection.

## Contracts

Each export reads saved composition, editorial content, referenced photographs and selected reference data through their existing owners. These reads do not share a cross-repository snapshot; validation completes before a downloadable file is published.

Export trusts repository validity guarantees and reuses authoritative geometry for print-readiness checks, original availability and identity, and effective image resolution. InDesign establishes local resources, template compatibility, fonts, glyphs and actual text composition.

The same JSON supplies both import scripts. It carries print content and explicit physical placement, retaining original identity and exact source-relative paths rather than relying on filenames alone.

The operator selects that JSON, maps its source roots and uses manually downloaded Saal templates for the final page count. Imports target fresh template copies; an incomplete import is reported and retried in another fresh copy.

Cleanup adapts the proven scripts to this handoff and removes obsolete input branches and duplicated knowledge. Changes to intended rendering require a demonstrated native mismatch and scoped approval, rather than inference from legacy source alone.

The [implementation design](design.md) defines responsibility changes separately from these observable guarantees.

## Acceptance

| ID | Observable outcome |
| --- | --- |
| A1 | One export action downloads one JSON containing the saved interior and cover, without promising isolation from concurrent edits across independently read data. |
| A2 | Export leaves saved content, browser Undo/Redo and editing ownership unchanged, including when another editor owns a spread. |
| A3 | Every attempt validates the entire saved book and reports actionable album, chapter, spread, slot or photo issues; blocking issues produce no download. |
| A4 | Missing, changed, unstable or undecodable referenced originals are reported explicitly; duplicate basenames never substitute another photograph. |
| A5 | Interior order, saved crop, zoom, mirror, caption corners and explicit bleed survive the handoff; both cover spine owners use the current page-dependent geometry. |
| A6 | Synopsis, descriptions, headings, captions, attribution and equipment selections retain their authored content and agreed print presentation. |
| A7 | Each importer rejects incompatible input, templates and unavailable required local resources before placement where detectable; later composition failures remain visibly incomplete. |
| A8 | Import completion reports font/glyph and text-overflow problems; approved font-specific diacritic removal affects only placed text, while remaining unsupported glyphs permit manual finishing without blocking placement. |
| A9 | Repeating an import cannot silently append a second book to an already populated document; original templates, retained documents and photographs remain untouched. |
| A10 | Export exposes accessible pending, validation and retry feedback, prevents duplicate pending requests and reads saved state afresh on retry without discarding drafts. |
| A11 | Shipped script logic is exercised directly; a bounded native fixture proves framing, bleed, text and path mapping before a representative complete-album import and final PDF inspection. |

## Print policy

| Decision | Policy |
| --- | --- |
| Validation policy | Warn below 300 effective DPI without blocking on resolution alone. Empty photo slots, synopsis and chapter descriptions block export; empty captions are allowed. |
| Authored characters | Saved text and the JSON retain authored characters exactly. InDesign removes diacritics from Ink Free synopsis/cover text and Dragon chapter headings; Garamond descriptions/captions retain them. Remaining unsupported glyphs warn for manual finishing without automatic font substitution. |
| Physical fidelity | Native reconciliation of legacy JSON bleed/cover offsets remains required before changing placement. |

## Doc Sync

| Owner | Enduring delta |
| --- | --- |
| specs/design.md — Album model / Architectural decisions | A print-only handoff composes existing saved-data reads; repository validity, print readiness and native composition have distinct authorities. |
| specs/contracts.md — Contract inventory | Link the authoritative export boundary and shared print representation consumed by both scripts. |
| specs/testing.md — Confidence model | Shipped-script checks establish orchestration; actual InDesign and final-output inspection establish print composition. |
