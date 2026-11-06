# Import a saved book

Use the InDesign icon beside Reader in the album workspace to export the saved book. The single JSON contains its interior and cover; unsaved browser edits are not included.

For low-resolution photographs, follow the warning links to review the affected slots, then select Confirm export to export the current saved book and close the warning. X cancels without downloading.

Copy this entire directory into InDesign's Scripts Panel folder, keeping the files together. No build step is required.

Download Saal's empty 42 x 28 hardcover IDML templates for the exported book's final page count. The interior template must contain one complete two-page spread per InDesign page.

Run `place-spreads.jsx` for the interior and `place-cover.jsx` for the cover, selecting the same JSON for both. Each run opens a fresh template copy; existing templates and documents are never overwritten.

Choose the local photographs folder corresponding exactly to the source prefix shown by the importer. Subfolders and filenames must match; a missing file is never replaced with a same-named photograph elsewhere.

For the interior, also select the local folder matching the shown flags prefix. The application ships those images in `frontend/public/flags`.

Install Ink Free Regular, IDDragonXing Regular and Garamond Premier Pro Regular before importing. Only Ink Free and IDDragonXing text loses diacritics during placement; the JSON and authored content remain unchanged.

The cover prints the album year beside the attribution. A matching year suffix in the album title is separated from the printed heading, retaining the established cover presentation without changing the saved title.

An import does not save or print automatically. Inspect all spreads, framing, captions, text, glyph warnings and cover attribution before saving and exporting PDFs with the template's document bleed.

If placement fails, the open copy is marked incomplete and must not be printed. Correct the reported problem and retry with a fresh template; an already attempted document cannot be imported again.
