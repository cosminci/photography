# Dagger testing

## What the automated tests prove

Selection component tests prove path parsing, owner mappings, shared-infrastructure expansion, unrelated-path exclusion and canonical component order. Change these tests with the mapping: an all-component build cannot detect a path that selective verification wrongly ignores.

Version component tests prove UTC generation, valid calendar dates and the [release format](contracts.md#callable-interface). They reject mutable tags, padded date components, invalid times and trailing newlines; they do not prove registry publication.

Verification-wrapper tests exercise real temporary Git repositories and subprocesses, substituting expensive build commands. They prove that host checks run for docs, stage failure stops execution and the pre-push hook rejects a different outgoing revision or dirty checkout; they do not execute the Dagger engine.

## Evidence limits

[Vitest configuration](../../.dagger/vitest.config.ts) excludes the module entrypoint, build constants and container hooks from coverage. Passing policy coverage therefore does not prove Git mounting, dependency installation, image assembly or publication.

Validate changed container hooks by executing the affected gates against the committed candidate. A successful application gate proves that build path, not unexecuted hooks; image assembly needs a build, and publication needs an authorized registry operation.

Use the shared [test nomenclature](../../specs/testing.md#nomenclature). Keep individual command results in change evidence rather than this document.
