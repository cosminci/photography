# Repository safety

Before changing persistent data or migrations, read [service recovery and authorization](../../../specs/operational.md#recovery-and-maintenance).

Before changing deployment, read the [NAS runbook](../../../unraid/README.md). Documentation or release work does not authorize a production operation.

## Living documentation

- Complement Scout with responsibilities, interactions, lifecycles, guarantees and rationale; omit implementation and test-source links.
- Use protocol/path/definition tables for contracts. Link OpenAPI, schemas, migrations and configuration; add only missing interpretation.
- Review current service and pipeline docs together. Remove repeated definitions; preserve operator entrypoints, recovery and safety instructions.
