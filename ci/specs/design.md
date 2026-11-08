# Pipeline design

## Purpose

The pipeline verifies candidate revisions and publishes authorized releases. Local pushes and pull requests use one verification entrypoint; affected-component selection avoids rebuilding unrelated application components.

```mermaid
flowchart TD
  Push["Local push"] --> Verify["Shared verification entrypoint"]
  PR["Pull request"] --> Verify
  Verify --> Shared["Markdown and pipeline-module checks"]
  Shared --> Selection["Changed paths against a Git baseline"]
  Selection --> Backend
  Selection --> Frontend
  Selection --> Pipeline
  All["Explicit all-component verification"] --> Backend & Frontend & Pipeline
```

Backend, frontend and pipeline gates own independent build boundaries in pinned environments. Selection follows path ownership, not file type: frontend-owned changes select the frontend gate; standalone living-doc changes retain shared checks without selecting an application component.

Changes to shared verification infrastructure or its environment select every component. The gates exercise actual application builds as well as selection policy.

## Verification boundary

The pre-push hook and pull-request CI converge on shared verification. [Operations](operational.md#local-push-verification) owns candidate/baseline selection; a failed stage stops the pipeline.

| Owner | Responsibility |
| --- | --- |
| Shared verification entrypoint | Markdown checks, pipeline-module validation and selected component execution. |
| Pipeline module | Path-selection policy and component gates. |
| Pre-push hook | Validate the outgoing checkout before invoking shared verification. |
| GitHub workflows | Invoke verification for pull requests, all-component main checks and authorized publication. |

[Contracts](contracts.md) defines verification completion; [operations](operational.md) owns invocation and baseline selection.

## Release boundary

Publication follows host-side module validation and every component gate, then builds and publishes the application image. [Release contracts](contracts.md) owns version identity and registry requirements.

Publication and release completion are separate outcomes. An image published before a later access-check or tagging failure retains its immutable version; recovery completes that release rather than replaces the image.

The [deployment topology](../../specs/operational.md#run-on-the-nas) owns downstream container/storage relationships; the [operator runbook](../../unraid/README.md) owns publication and installation actions.
