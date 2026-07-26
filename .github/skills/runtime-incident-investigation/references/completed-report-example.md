# Completed Report Example

This report is fictional. Names, identifiers, paths, and values are examples and are not operational data.

## 1. Executive Conclusion

**Status: PROBABLE**

The test order confirmation failed because the Orders service treated a transient Inventory timeout as an unrecoverable exception after its single configured attempt. Runtime logs show the timeout and HTTP 500 response, and the inspected code shows the matching exception path. The conclusion remains probable because the deployed commit could not be independently verified.

## 2. Incident Facts

- Environment: `test`.
- Reported time: `2026-07-25 14:00 Asia/Shanghai`.
- API: `POST /api/orders/ORD-10023/confirm`.
- Observed result: HTTP 500.
- Expected result: the order should be confirmed.
- Trace ID: `abc-123`.
- Business ID: `ORD-10023`.

Source: reporter-provided context (`CTX-001`).

## 3. Service Identification

`orders-service` ranked first with a score of 160:

- API pattern `/api/orders/**` matched.
- Exact service alias `orders` matched the request context.
- Owned table `app_order` matched the business object.

The next candidate scored 20. The lead and matched API ownership support selecting `orders-service`.

## 4. Investigation Scope and Selected Evidence Lanes

Selected:

- Logs to establish the observed request, dependency timeout, and response.
- Code to map timeout configuration, exception handling, and response mapping.
- Database to verify whether confirmation state committed.

No other service repository was inspected. Inventory logs were not available during the bounded investigation, which limits the downstream-cause claim.

## 5. Evidence Ledger

### CTX-001

- Source type: context.
- Source location: QA incident report `QA-EXAMPLE-1042`.
- Environment: test.
- Timestamp: `2026-07-25 14:00 Asia/Shanghai`, approximate.
- Observed fact: QA reported HTTP 500 for order `ORD-10023` with trace `abc-123`.
- Interpretation: Defines the investigation target.
- Supports: `HYP-001`.
- Refutes: none.
- Confidence: medium; reporter time is approximate.
- Limitations: no captured response body.
- Does not prove: which service or condition caused the response.
- Redactions: reporter identity omitted.

### LOG-001

- Source type: log.
- Source location: SSH alias `test-orders-01`, `/var/log/orders/application.log`.
- Environment: test.
- Timestamp: `2026-07-25T14:02:11.481+08:00`.
- Observed fact: Trace `abc-123` entered `POST /api/orders/ORD-10023/confirm`.
- Interpretation: The request reached the Orders service.
- Supports: `HYP-001`.
- Refutes: `HYP-003` client-side-only failure.
- Confidence: high; exact trace and API matched.
- Limitations: search covered `13:57` through `14:07`.
- Does not prove: why the request failed.
- Redactions: actor identifier replaced with `[USER]`.

### LOG-002

- Source type: log.
- Source location: SSH alias `test-orders-01`, `/var/log/orders/application.log`.
- Environment: test.
- Timestamp: `2026-07-25T14:02:13.512+08:00`.
- Observed fact: Trace `abc-123` recorded `InventoryClientTimeout` after approximately 2,000 ms, followed by response status 500.
- Interpretation: The user-visible failure followed the downstream timeout in the same trace.
- Supports: `HYP-001`.
- Refutes: `HYP-002` validation rejection.
- Confidence: high; ordered events share the exact trace.
- Limitations: Inventory service logs were unavailable.
- Does not prove: why Inventory did not respond.
- Redactions: downstream host and token removed.

### CODE-001

- Source type: code.
- Source location: `orders-service/src/main/java/example/orders/OrderConfirmationService.java:88-104`.
- Environment: not applicable.
- Revision: local commit `example7`.
- Observed fact: `InventoryClientTimeout` is rethrown from the confirmation path without a retry or fallback.
- Interpretation: If the deployed revision matches, the observed timeout follows the unrecoverable exception path.
- Supports: `HYP-001`.
- Refutes: `HYP-004` successful retry.
- Confidence: medium; deployed revision was not independently verified.
- Limitations: dynamic configuration may alter client behavior.
- Does not prove: that this code ran in the incident.
- Redactions: none.

### CODE-002

- Source type: code.
- Source location: `orders-service/src/main/java/example/orders/ApiExceptionHandler.java:41-55`.
- Environment: not applicable.
- Revision: local commit `example7`.
- Observed fact: an unhandled `InventoryClientTimeout` maps to HTTP 500.
- Interpretation: This mapping explains the logged response if the inspected revision was deployed.
- Supports: `HYP-001`.
- Refutes: `HYP-002`.
- Confidence: medium; deployed revision was not independently verified.
- Limitations: framework advice ordering was inspected only in this service.
- Does not prove: the exception occurred at runtime without `LOG-002`.
- Redactions: none.

### DB-001

- Source type: database.
- Source location: connection `orders_test`, table `orders.app_order`.
- Environment: test.
- Timestamp: query executed `2026-07-25T14:20:00+08:00`.
- Observed fact: The bounded query for `ORD-10023` returned status `PENDING`, version `4`, updated before the incident.
- Interpretation: No confirmed order state was visible at query time.
- Supports: `HYP-001`.
- Refutes: `HYP-005` confirmation committed despite the response.
- Confidence: medium; replica lag was not measured.
- Limitations: current-state query, not a complete history.
- Does not prove: which component prevented or wrote the state.
- Redactions: customer and payment fields were not selected.

## 6. Reconstructed Timeline

| Time | Event |
|---|---|
| Approximately 14:00 | QA initiated confirmation (`CTX-001`). |
| 14:02:11.481 | Orders accepted the request (`LOG-001`). |
| 14:02:13.512 | Inventory call timed out after about two seconds (`LOG-002`). |
| Immediately after | The same trace returned HTTP 500 (`LOG-002`). |
| 14:20:00 | The order remained `PENDING` in the bounded query (`DB-001`). |

All runtime timestamps are shown in `Asia/Shanghai`.

## 7. Causal Chain

```text
Trigger: QA submits order confirmation (CTX-001)
→ Entry point: Orders receives POST /api/orders/ORD-10023/confirm (LOG-001)
→ Code path: confirmation invokes Inventory and rethrows InventoryClientTimeout (CODE-001)
→ Runtime behavior: Inventory call times out in trace abc-123 (LOG-002)
→ Data or state condition: order remains PENDING at query time (DB-001)
→ Failed condition: timeout reaches the generic error mapper without retry or fallback (CODE-001, CODE-002)
→ User-visible result: the trace returns HTTP 500 (LOG-002)
```

The link between local code and the deployed runtime is inferred because deployment metadata was unavailable.

## 8. Alternative Hypotheses and Elimination

- `HYP-002`, validation rejected the request: refuted by `LOG-001`, `LOG-002`, and the exception mapping in `CODE-002`.
- `HYP-003`, the failure occurred only in the client: refuted by server-side trace evidence in `LOG-001`.
- `HYP-004`, a retry eventually succeeded: refuted for the inspected code by `CODE-001` and not observed in the bounded trace.
- `HYP-005`, confirmation committed despite HTTP 500: not supported by `DB-001`, subject to replica and current-state limitations.
- Inventory saturation or network interruption: unresolved because Inventory logs and metrics were unavailable.

## 9. Root Cause

The probable immediate cause was an Inventory client timeout that the Orders confirmation path converted into HTTP 500 without retry or fallback (`LOG-002`, `CODE-001`, `CODE-002`). The underlying cause of the Inventory response delay is not established.

## 10. Immediate Mitigation

After operational approval, retry the affected test case when Inventory health is confirmed. Do not directly change the order row. If repeated confirmation is not idempotent, first validate the idempotency path and use an approved application-level recovery operation.

## 11. Permanent Fix

Implement a bounded retry or explicit degraded response only if product and reliability requirements permit it. Preserve idempotency, distinguish timeout from permanent rejection, and map an exhausted dependency timeout to an intentional API error. Investigate and address the separate Inventory latency cause.

## 12. Validation and Regression Tests

- Unit-test timeout mapping and exhausted retry behavior.
- Integration-test one transient timeout followed by success.
- Verify no duplicate reservation or confirmation event is created.
- Deploy to test, capture the exact commit, and repeat with correlated logs.
- Query only the target order and event history to confirm one valid transition.

## 13. Rollback Plan

Roll back the application release if timeout rate, duplicate operations, or latency increases beyond the approved threshold. Verify the prior commit is active, repeat a healthy confirmation, and confirm no in-flight retries remain. Any data recovery requires separate approval.

## 14. Monitoring and Prevention

- Record dependency latency, timeout count, retry count, and exhausted retry count by endpoint.
- Alert on elevated confirmation 5xx rate and Inventory timeout rate.
- Include deployment revision and correlation ID in structured logs.
- Add an idempotency regression gate for confirmation.

## 15. Ready-to-Send Reply

We found that this test request reached the Orders service and then timed out while waiting for Inventory. The Orders service returned HTTP 500, and the order was still pending when we checked it. The most likely cause is that the current confirmation path does not recover from this dependency timeout. We have not yet confirmed why Inventory responded slowly, so the conclusion is probable rather than confirmed. We recommend retrying only after Inventory is healthy and after confirming the operation is safe to repeat. The permanent fix will add tested timeout handling and clearer error behavior. No action is required from QA except to retain the trace ID for validation.

## 16. Remaining Unknowns

- The exact deployed Orders commit.
- Inventory logs and latency metrics for the same trace window.
- Replica lag at the database query time.

These gaps prevent a `CONFIRMED` classification and prevent attribution of the downstream delay.
