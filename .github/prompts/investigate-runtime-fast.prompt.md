---
name: Investigate Runtime Fast
description: Start a low-latency, single-service runtime investigation.
agent: Runtime Incident Fast
---

Investigate this issue in Runtime Incident Fast mode.

- Service: **REQUIRED**
- Local repository or service catalog key: **REQUIRED**
- Environment: **REQUIRED**
- Approximate time (include timezone): **REQUIRED**
- API or symptom: **REQUIRED**
- Trace ID or business ID: provide when available

Treat the service as a hard scope boundary. Do not use workspace-wide search or subagents. Use only necessary evidence lanes, enforce the Fast budgets, and return `NEEDS_DEEP_INVESTIGATION` rather than expanding scope.
