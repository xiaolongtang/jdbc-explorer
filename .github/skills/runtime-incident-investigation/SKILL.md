---
name: runtime-incident-investigation
description: Investigate API failures, runtime exceptions, incorrect states, missing data, and cross-service issues by correlating local source code, remote service logs through SSH MCP, and read-only database evidence through JDBC MCP. Use for issues reported in test, QA, staging, or similar runtime environments when a complete evidence-backed root-cause analysis, repair plan, and stakeholder response are required.
argument-hint: "[environment] [approximate time] [API or symptom] [trace ID or business ID]"
---

# Runtime Incident Investigation

Use this skill for runtime incidents in test, QA, staging, or similar non-production environments when a defensible causal analysis is required. Do not use it for feature implementation, speculative architecture review, production mutation, data repair, service control, or an approved fix that should now be implemented.

## Non-negotiable rules

- Keep the entire investigation read-only. Do not modify source code, server files, services, infrastructure, or database data.
- Keep source facts separate from interpretations and assumptions.
- Never invent missing evidence or treat an inaccessible source as support.
- Classify the final root cause as `CONFIRMED`, `PROBABLE`, or `INCONCLUSIVE`.
- Stop only when the evidence standard is met or the remaining gaps are explicitly reported.

## Run the workflow

1. Read [workflow](resources/workflow.md) and [routing and escalation](resources/routing-and-escalation.md).
2. Apply the [evidence standard](resources/evidence-standard.md) and [security and redaction policy](resources/security-and-redaction.md).
3. Read and validate the local [service catalog](assets/service-catalog.local.json), using the [example catalog](assets/service-catalog.example.json) and [catalog reference](references/service-catalog-reference.md).
4. Normalize partial input with the [incident request template](assets/incident-request.template.json), then run [catalog validation](scripts/validate_service_catalog.py) and [service resolution](scripts/resolve_service.py).
5. Select only the necessary lanes and delegate them independently. Use [code investigation](resources/code-investigation.md), [log investigation](resources/log-investigation.md), and [database investigation](resources/database-investigation.md).
6. Give the synthesizer only collected evidence and the active hypotheses. Follow [synthesis and response](resources/synthesis-and-response.md).
7. Produce the final result with the [report template](assets/investigation-report.template.md) and [stakeholder reply template](assets/stakeholder-reply.template.md).

## Supporting material

- Evidence data: [evidence record schema](assets/evidence-record.schema.json)
- MCP discovery: [MCP tool mapping](references/mcp-tool-mapping.md)
- Intake examples: [incident input examples](references/incident-input-examples.md)
- Safe searches and queries: [query and log patterns](references/query-and-log-patterns.md)
- Expected output: [completed report example](references/completed-report-example.md)
- Hook guards: [read-only guard](scripts/guard_read_only.py) and [evidence contract injector](scripts/inject_evidence_contract.py)
- Redaction: [redact text](scripts/redact_text.py)
- Repository verification: [self-check](scripts/self_check.py)

Treat templates and examples as structure, not evidence. Preserve exact evidence IDs and source locations in every handoff.
