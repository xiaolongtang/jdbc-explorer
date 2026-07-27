# Runtime Incident Investigation Kit

This repository provides two explicitly separated, read-only VS Code GitHub Copilot modes for source, SSH log, and JDBC evidence:

- **Runtime Incident Fast** is the default for routine QA, BA, test, and staging incidents. It has direct read-only SSH/JDBC access, no subagents, one hard service boundary, and strict budgets.
- **Runtime Incident Deep** is manually selected for complex incidents. It performs its own scoped code searches and may call no more than the Deep-only Log Evidence and Database Evidence agents.

Fast never selects Deep automatically. There is no Code Evidence or Incident Synthesizer subagent.

## Configure the catalog

Copy `.github/skills/runtime-incident-investigation/assets/service-catalog.example.json` to the ignored `service-catalog.local.json`, replace every sample/TODO value, and keep credentials out of it. Repository paths are inputs to the physical scoped-search guard; they do **not** restrict VS Code built-in workspace search.

Validate it with:

```bash
python3 .github/skills/runtime-incident-investigation/scripts/validate_service_catalog.py .github/skills/runtime-incident-investigation/assets/service-catalog.local.json
```

The JDBC server in this repository exposes the specific read-only `jdbc-explorer/executeQuery` tool used by Fast. SSH tool names depend on the installed server, so the checked-in agents use `ssh-mcp-server/*` behind the read-only hook. Replace that wildcard with the installed server's specific bounded log-search tool when known.

## Workspace scope

**Preferred:** open only the target service repository and the investigation-kit folder in the current VS Code workspace.

**Acceptable:** retain a multi-root workspace, while recognizing that built-in workspace search remains prohibited in Fast mode. The included workspace contains only this kit and one `TODO_REPLACE_ACTIVE_SERVICE` folder. It is a convenience, not a security boundary.

## Run Fast mode

Select `Runtime Incident Fast` and use `.github/prompts/investigate-runtime-fast.prompt.md`. Supply the service/catalog key, environment, approximate time with timezone, API or symptom, and trace or business ID when available. The service is a hard boundary. Fast uses symptom-driven evidence, discovers code only through `scoped_code_search.py`, and stops rather than exceeding its budgets.

Fast permits at most 12 total tool calls, 3 code searches, 5 source-file reads, 3 log searches, 2 SQL queries, 1 primary service, and 1 user-approved downstream service after direct runtime evidence. Its report has seven sections. A budget or scope shortfall returns `NEEDS_DEEP_INVESTIGATION` with the unresolved question and next Deep evidence action.

## Run Deep mode

Select `Runtime Incident Deep` manually only for cross-service, intermittent, contradictory, asynchronous, transactional, or similarly complex incidents. Declare bounded scope and budgets. Deep may call a maximum of two evidence subagents in total and only when their log/database lanes are materially required. Deep synthesizes the returned ledgers itself.

## Safety

The PreToolUse hook permits local execution only of `scoped_code_search.py` and `validate_service_catalog.py`; it rejects raw recursive search, compilation, tests, Git, shell composition, redirection, arbitrary Python, and modification commands. It also preserves bounded read-only SSH and single-statement `SELECT` JDBC checks. Hooks are defense in depth: use read-only server accounts and database grants. Raw commands, SQL, logs, results, and sensitive values are not audit-logged.

## Validate the kit

```bash
python3 -m compileall -q .github/skills/runtime-incident-investigation/scripts .github/skills/runtime-incident-investigation/tests
python3 .github/skills/runtime-incident-investigation/scripts/validate_service_catalog.py .github/skills/runtime-incident-investigation/assets/service-catalog.example.json
python3 -m unittest discover -s .github/skills/runtime-incident-investigation/tests -v
python3 .github/skills/runtime-incident-investigation/scripts/self_check.py
```

On Windows, use `py -3` instead of `python3`. Remaining local values are the active repository paths, service/environment aliases, SSH targets and log paths, JDBC connection aliases and schema metadata, and (when available) the exact read-only SSH log-search tool name.
