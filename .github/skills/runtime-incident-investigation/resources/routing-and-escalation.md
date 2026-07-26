# Routing and Escalation

Select the smallest evidence scope that can test the initial hypotheses. A lane is not required merely because it is available.

## Decision matrix

| Incident signal | Initial evidence lanes | Reason |
|---|---|---|
| Deterministic validation or input rejection | Code | Map the input rule, handler, and returned error. Add logs only if the observed response differs from the deterministic path. |
| HTTP 500 or stack trace | Logs and code | Logs establish observed execution; code maps the exception and handling path. |
| Timeout or downstream failure | Logs and code | Logs reconstruct timing and dependency failure; code establishes timeout, retry, and fallback behavior. |
| Missing, stale, duplicated, or incorrect data | Database and code | Database evidence establishes bounded state; code identifies readers, writers, constraints, and transitions. |
| Unknown writer or unexplained state transition | Database, logs, and code | State alone does not identify the writer; correlate state, execution, and write paths. |
| Intermittent or cross-service failure | Logs across candidate services and code | Compare correlated timelines, then inspect the relevant call, retry, cache, messaging, or concurrency paths. |
| Contradictory evidence | All relevant lanes | Resolve clock, deployment, routing, writer, cache, and persistence differences without discarding conflict. |
| Only an approximate time and description | Service resolution, then the narrowest plausible lane | First improve service confidence through catalog signals; use a bounded log search only when a candidate and time window exist. |

## Stop early

Stop collecting when all of the following are true:

1. The responsible service is supported by evidence.
2. Every causal-chain link required for the chosen conclusion is supported or explicitly classified as an inference.
3. Material alternatives are refuted or shown to be non-material.
4. No evidence conflict remains unexplained.
5. The result can support a safe mitigation, permanent-fix direction, and validation plan.

Do not open a database lane for a pure input-validation failure already demonstrated by matching request and code evidence. Do not inspect unrelated services after a correlated log timeline identifies the failing boundary.

## Expand the investigation

Expand one bounded step at a time when:

- A selected lane cannot distinguish two material hypotheses.
- A trace crosses a downstream boundary not in the original scope.
- Runtime logs show a code path different from the inspected revision.
- Database state conflicts with logged writes or expected transaction behavior.
- Time zones, deployments, retries, caches, asynchronous processing, or replicas could explain a contradiction.
- A required source is unavailable and another lane can partially test the same link.

State the new question, lane, service, time window, and stopping condition before expansion.

## Do not expand

Do not broaden scope to search for general anomalies, dump complete logs or tables, inspect unrelated environments, or compensate for missing evidence with speculation. If critical evidence remains inaccessible, preserve the gap and lower the conclusion status.
