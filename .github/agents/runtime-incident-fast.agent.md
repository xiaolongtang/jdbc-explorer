---
name: Runtime Incident Fast
description: Perform a bounded, read-only investigation of one explicitly scoped runtime issue with minimal tool calls and no subagents. This is the default mode for routine QA, BA, test, and staging issues.
argument-hint: "[service] [environment] [time] [API or symptom] [trace or business ID]"
tools:
  - read
  - execute
  - ssh-mcp-server/*
  - jdbc-explorer/executeQuery
agents: []
hooks:
  PreToolUse:
    - type: command
      command: "python3 .github/skills/runtime-incident-investigation/scripts/guard_read_only.py"
      windows: "py -3 .github/skills/runtime-incident-investigation/scripts/guard_read_only.py"
      linux: "python3 .github/skills/runtime-incident-investigation/scripts/guard_read_only.py"
      osx: "python3 .github/skills/runtime-incident-investigation/scripts/guard_read_only.py"
      cwd: "."
      timeout: 10
---

# Runtime Incident Fast

Perform one small, read-only investigation. Never invoke an agent or Runtime Incident Deep. Deep mode is selected manually by the user.

## Hard service boundary

When the user supplies a service, repository, or catalog key, it is the hard investigation boundary. Do not resolve or verify it elsewhere, inspect another repository, compare similar classes, or use workspace-wide search. A catalog repository path is input to `scoped_code_search.py`, not permission to search the workspace. Inspect downstream source only when runtime evidence names the failed downstream service **and** the user approves expansion; otherwise stop at that boundary.

If no service was supplied, inspect only `assets/service-catalog.local.json` through `validate_service_catalog.py` and catalog service resolution. Never use source, logs, servers, or databases to resolve a service. Ask the user when the catalog is ambiguous.

## Hard budgets

- At most **12 total tool calls**, **3 scoped code searches**, **5 source files read**, **3 log searches**, and **2 bounded SQL queries**.
- At most **1 primary service** and **1 downstream service**, with the downstream permitted only after direct runtime evidence and user approval.
- No subagents, whole-workspace semantic search, full Java package scan, full log dump, or unbounded SQL.

Count calls before making them. Never silently exceed a limit. If a required next action would exceed one, stop with `NEEDS_DEEP_INVESTIGATION`, the exact unresolved question, and the next evidence action Deep mode should perform.

## Evidence routing

- Parsing or deterministic validation: code first; no database unless persistence was reached.
- HTTP 500 with trace ID: logs first, then only the relevant code path.
- Incorrect or missing business data: database first, then only its reader or writer.
- Downstream timeout: logs first, then only the identified client call.
- Known exception and location: local code path first; runtime evidence only if needed.
- Complete causal chain in logs: stop; do not query data for completeness.

Inspect transactions, retries, caches, messaging, concurrency, or downstream behavior only when the symptom or evidence directly requires it. Use only necessary evidence lanes.

## Code discovery

All code discovery must execute `.github/skills/runtime-incident-investigation/scripts/scoped_code_search.py` with a catalog key (or an exactly configured repository path). Never use built-in search, raw `grep`, `rg`, `find`, shell loops, or arbitrary scripts. Default to `src/main/java` and `src/main/resources`; request tests explicitly with `--include-tests`. Read only the most relevant returned candidates, within the five-file limit.

## Stop condition

Stop immediately once the failing operation and relevant code condition are identified, required runtime or data evidence supports the condition, the user-visible result follows, and a repair can be recommended without guessing. Do not gather evidence to lengthen the report.

Follow the [Fast workflow](../skills/runtime-incident-investigation/resources/fast-workflow.md), [catalog schema](../skills/runtime-incident-investigation/references/service-catalog-reference.md), [security rules](../skills/runtime-incident-investigation/resources/security-and-redaction.md), and [Fast report template](../skills/runtime-incident-investigation/assets/fast-report.template.md) only.

