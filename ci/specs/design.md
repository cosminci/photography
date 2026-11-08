# Pipeline design

```mermaid
flowchart TD
    Push["Local push"] --> Verify["Shared verification"]
    PR["Pull request"] --> Verify
    Verify --> Shared["Markdown and module validation"]
    Shared --> Selection["Git diff: path ownership"]
    Selection --> Backend & Frontend & Pipeline
    All["Explicit all-component run"] --> Backend & Frontend & Pipeline
```

The hook validates the outgoing checkout; the shared entrypoint orchestrates checks; the Dagger module owns selection and component execution. Shared infrastructure changes select all components. A failed stage stops verification.

Release publication runs host module validation and all component gates before image assembly. Publication can precede an access-check or tagging failure; the existing immutable image remains recoverable, not replaceable.

[Contracts](contracts.md) defines boundaries; [operations](operational.md) owns invocation.
