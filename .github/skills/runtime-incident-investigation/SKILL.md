---
name: runtime-incident-investigation
description: Run a read-only runtime investigation in explicitly selected Fast or Deep mode.
argument-hint: "[service] [environment] [time] [API or symptom] [trace or business ID]"
---

# Runtime Incident Investigation

Use **Runtime Incident Fast** by default for normal QA, BA, test, and staging issues. It is single-agent, limited to one configured service repository, and governed by hard tool and evidence budgets. It must never invoke Deep mode.

Use **Runtime Incident Deep** only when the user manually selects it for cross-service, intermittent, contradictory, asynchronous, transactional, or otherwise complex evidence. Deep mode may use at most Log Evidence and Database Evidence, and at most two subagents total.

Both modes are read-only. A supplied service is a hard boundary; never search other repositories to verify it. If absent, resolve only from the service catalog and ask when ambiguous. Select evidence by symptom, and stop as soon as a supported causal chain and non-speculative repair recommendation are available. Never collect a lane merely for completeness.

## Load only the selected mode

Fast loads only the [Fast workflow](resources/fast-workflow.md), [service catalog schema](references/service-catalog-reference.md), [security rules](resources/security-and-redaction.md), and [Fast report](assets/fast-report.template.md).

Deep may additionally load the [Deep workflow](resources/workflow.md), [routing guidance](resources/routing-and-escalation.md), [evidence standard](resources/evidence-standard.md), lane resources, extended query guidance, [sixteen-section report](assets/investigation-report.template.md), and completed examples. Those Deep materials are not part of the normal Fast loading path.

All code discovery in either mode uses [scoped code search](scripts/scoped_code_search.py), one catalog-configured repository at a time. Apply the [read-only hook](scripts/guard_read_only.py).
