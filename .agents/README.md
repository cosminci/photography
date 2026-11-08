# Agent context

Start at [AGENTS.md](../AGENTS.md). This directory registers the shared workflow and contains repository-specific guidance.

[Workflow registration](workflows.json) selects the product owner and profile. The machine-local `agentic.workflowsPath` Git setting selects the shared checkout.

If resolution fails, set `agentic.workflowsPath` to an available shared checkout containing `scripts/workflows.py`, then retry the router.
