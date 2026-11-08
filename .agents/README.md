# Shared workflow registration

[The SDD router](skills/sdd/SKILL.md) resolves the shared workflow rather than copying its rules into this repository. [Registration](workflows.json) selects the repository root and Typelevel profile.

If resolution fails, inspect `git config --path --get agentic.workflowsPath`. It must identify an available shared checkout containing `scripts/workflows.py`; [the launcher](../scripts/workflows) reports missing registration and unavailable checkout separately.
