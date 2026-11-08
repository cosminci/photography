# Contributing

## Setup

Install [mise](https://mise.jdx.dev/), then run `mise install` from the repository root for the [pinned toolchain](.mise.toml). Provide sbt, Python 3 and a running Docker engine; local startup also needs npm, while Dagger executes component tools inside containers.

Enable publication checks once per clone:

```sh
git config core.hooksPath .githooks
```

For local application startup and isolated state, use [service operations](specs/operational.md#run-locally).

## Change workflow

Read [design principles](DESIGN-PRINCIPLES.md) and resolve shared guidance with `bash scripts/workflows context`. The returned conventions and Typelevel profile own coding rules; the returned writing skill supplies STE-informed language guidance.

Use current [service specs](specs/) for product intent and [Dagger specs](ci/specs/) for build behavior. The shared workflow decides when a proposal/approval is required; preserve other agents' branches and use an isolated worktree for independent work.

## Validation

Run the smallest owning component gate while developing; [Dagger operations](ci/specs/operational.md#select-and-check) lists direct commands. Extend the [owning test suite](specs/testing.md#nomenclature) for changed guarantees.

Before publishing, commit the candidate, fetch the target branch and run the shared entrypoint:

```sh
git fetch origin main
bash scripts/verify origin/main
```

[The wrapper](scripts/verify) checks changed Markdown, validates the Dagger module on the host and runs selected component gates. Direct Dagger verification omits those host checks; use its all-component mode in addition when shared impact is uncertain.

The [pre-push hook](.githooks/pre-push) requires the outgoing revision to be checked-out HEAD with no tracked or non-ignored changes. Feature pushes compare against fetched remote main; an existing remote-main update compares against its previous revision.

Preserve signing and repository identity, keep execution evidence in ignored `.agent-work/<slug>`, and publish only after final-source review and required gates pass. Passing coverage does not replace missing behavior or safety evidence.
