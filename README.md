# Runtime Incident Investigation Kit

This repository contains a reusable, read-only VS Code GitHub Copilot workflow for investigating runtime incidents across local source code, remote service logs, and environment databases. The kit coordinates narrowly scoped evidence agents and produces a complete causal report, repair plan, and stakeholder-ready response without changing code or runtime systems.

## Architecture

The `Runtime Incident Investigator` custom agent routes work to four allowed subagents:

- `Code Evidence` maps the reported API or symptom to exact source paths and possible code behavior.
- `Log Evidence` collects bounded runtime evidence through `ssh-mcp-server`.
- `Database Evidence` runs bounded read-only queries through `jdbc-explorer`.
- `Incident Synthesizer` cross-checks evidence, reconstructs the timeline and causal chain, and writes the final report.

The runtime skill in `.github/skills/runtime-incident-investigation` provides routing rules, evidence standards, references, templates, configuration assets, and deterministic safety scripts. `PreToolUse` hooks deny unsafe SSH and JDBC operations, and a `SubagentStart` hook injects the evidence contract.

## Prerequisites

- A current VS Code release with GitHub Copilot Chat, custom agents, agent skills, subagents, and hooks available.
- Python 3.10 or later. All included scripts use only the Python standard library.
- A locally registered SSH MCP server for read-only log access.
- A locally registered JDBC MCP server for read-only database access.
- Local clones of the services that may be investigated.

Never place credentials in this repository. Configure authentication in the MCP servers or an approved secret store.

## Configure the service catalog

1. Open `.github/skills/runtime-incident-investigation/assets/service-catalog.local.json`.
2. Replace every `TODO_REPLACE_*` value with a local path, API pattern, SSH target alias, log path, database connection alias, schema, table, business key, trace field, or timestamp field.
3. Add or remove services and environments as needed while preserving the documented schema.
4. Keep connection aliases only. Do not add JDBC URLs containing passwords, passwords, tokens, private keys, or access keys.
5. Validate the file before an investigation.

The local catalog is intentionally ignored by Git. Use `service-catalog.example.json` as the shareable reference.

## Configure MCP server IDs

The checked-in agents expect the MCP IDs `ssh-mcp-server` and `jdbc-explorer`. If VS Code registers different IDs:

1. Open the Chat view and use **Configure Tools** or the Agent Customizations diagnostics view to inspect the exact MCP tool prefixes.
2. Replace `ssh-mcp-server/*` in `.github/agents/log-evidence.agent.md`.
3. Replace `jdbc-explorer/*` in `.github/agents/database-evidence.agent.md`.
4. Update the corresponding identifiers in `guard_read_only.py` and `self_check.py`.
5. Re-run the validation commands below.

Exact MCP tool names vary by implementation. Keep the agents restricted to read-only log and query tools.

## Open the investigation workspace

Replace the six `TODO_REPLACE_SERVICE_*` paths in `incident-investigation.code-workspace` with local service repository paths. Then open that multi-root workspace in VS Code. The first folder is this kit repository; the remaining folders provide local code evidence for candidate services.

## Enable and run the workflow

The workspace enables agent-scoped hooks with:

```json
{
  "chat.useCustomAgentHooks": true
}
```

Open the Chat view, select `Runtime Incident Investigator`, and submit an incident description. A recommended prompt is:

```text
Investigate this test-environment issue.

Environment: test
Approximate time: 2026-07-25 14:00 Asia/Shanghai
API: POST /api/orders/ORD-10023/confirm
Observed: The API returned HTTP 500.
Expected: The order should be confirmed.
Trace ID: abc-123
Business ID: ORD-10023

Identify the responsible service, select only the necessary evidence lanes, and
produce a complete evidence-backed investigation report, repair plan, and a
ready-to-send response for QA.
```

Partial input is supported. The agent asks one concise question only when missing facts prevent safe progress.

## Investigation lane selection

The workflow starts with the smallest defensible scope:

- Deterministic validation behavior uses code evidence.
- HTTP 500 errors, stack traces, timeouts, and downstream failures use logs and code.
- Missing, stale, duplicate, or incorrect data uses database and code.
- Unknown writers, unexplained transitions, contradictions, and complex cross-service failures expand to all relevant lanes.

Independent lanes run in parallel when possible. The workflow stops early only when the selected evidence supports a complete causal chain and material alternatives are eliminated. Every conclusion is classified as `CONFIRMED`, `PROBABLE`, or `INCONCLUSIVE`.

## Read-only and security guarantees

- Investigation agents have no editing tools.
- The main orchestrator has no direct SSH or JDBC tools.
- JDBC hooks allow only one bounded `SELECT` or read-only `WITH ... SELECT` statement and deny `FOR UPDATE`.
- SSH hooks allow a small set of bounded read commands and deny redirection, mutation, deployment, package installation, container mutation, and service-control commands.
- The workflow separates observations from interpretations, never invents evidence, and reports gaps explicitly.
- Logs and database results are minimized and redacted. Raw prompts, MCP arguments, rows, and logs must not be audit-logged.

Hooks are a safety layer, not a substitute for read-only server accounts and database grants.

## Validate the kit

From the repository root, run:

```bash
python3 .github/skills/runtime-incident-investigation/scripts/validate_service_catalog.py \
  .github/skills/runtime-incident-investigation/assets/service-catalog.example.json
python3 .github/skills/runtime-incident-investigation/scripts/validate_service_catalog.py \
  .github/skills/runtime-incident-investigation/assets/service-catalog.local.json
python3 -m compileall -q .github/skills/runtime-incident-investigation/scripts
python3 .github/skills/runtime-incident-investigation/scripts/self_check.py
```

On Windows, replace `python3` with `py -3`.

## Troubleshooting

- **Agents do not appear:** Open the multi-root workspace, verify `.github/agents`, reload VS Code, and inspect Chat customization diagnostics.
- **Hooks do not run:** Confirm `chat.useCustomAgentHooks` is enabled, Python is available, and the hook script paths resolve from the kit repository root.
- **An MCP tool is unavailable:** Compare the registered MCP ID and tool name with the declarations in the log or database agent.
- **The catalog fails validation:** Follow each reported JSON path and replace missing, blank, duplicate, or credential-like fields.
- **Service resolution is ambiguous:** Add a trace, API path, table, exception, workspace, or exact service alias. Do not silently pick a low-confidence candidate.
- **A safe command is denied:** Narrow it to one supported read operation. Do not bypass the guard with shell composition or redirection.
- **Evidence is incomplete:** Report the missing lane or inaccessible source and use `PROBABLE` or `INCONCLUSIVE`; never upgrade confidence without evidence.

The existing Java source in this repository implements the `jdbc-explorer` MCP server used by the database evidence agent. Its build remains available through `./mvnw clean package` on the `main-jdk-17` branch.
