# Repository safety

Before changing persistent data or migrations, read [service recovery and authorization](../../../specs/operational.md#recovery-and-maintenance).

Before changing deployment, read the [NAS runbook](../../../unraid/README.md). Documentation or release work does not authorize a production operation.

## Living documentation

Living docs complement code discovery: explain responsibilities, interactions, guarantees and rationale. Code and suite discovery belong to Scout; links to implementation classes, modules or test sources are prohibited.

Contracts identify each external boundary by its protocol or format and concrete resource paths or callable commands. Link authoritative OpenAPI, schemas, migrations and configuration where they own exact definitions; keep operator commands and runbook entrypoints in operations.

Before finishing a living-doc edit, review all current service and pipeline docs for implementation pointers and duplicated definitions, preserving recovery, safety and boundary interpretation.
