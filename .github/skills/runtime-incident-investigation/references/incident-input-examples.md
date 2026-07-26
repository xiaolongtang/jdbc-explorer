# Incident Input Examples

These examples show minimum useful input and expected initial lanes. Lane selection may expand only under the documented escalation rules.

## 1. HTTP 500 with a trace ID

```text
Environment: test
Approximate time: 2026-07-25 14:00 Asia/Shanghai
API: POST /api/orders/ORD-10023/confirm
Status: 500
Trace ID: abc-123
```

Expected lanes: logs and code.

## 2. Incorrect business status

```text
Environment: staging
Observed: Order ORD-20411 shows SHIPPED after cancellation.
Expected: The status should remain CANCELLED.
Approximate time: 2026-07-25 09:30 UTC
```

Expected lanes: database and code. Add logs if the writer or transition remains unknown.

## 3. Missing record

```text
Environment: test
Business ID: INV-77821
Observed: The invoice is absent from the search response.
Expected: The invoice should appear after order completion.
```

Expected lanes: database and code. Add logs if creation execution must be established.

## 4. Downstream timeout

```text
Environment: staging
API: GET /api/customer-summary/CUS-441
Observed: Request timed out after approximately 30 seconds.
Correlation ID: corr-908
```

Expected lanes: logs and code, including the named downstream boundary if correlated logs require expansion.

## 5. Intermittent cross-service issue

```text
Environment: test
Observed: About one in twenty payment confirmations remains pending.
Trace IDs: tr-201, tr-247, tr-288
Expected: All accepted payments should reach CONFIRMED.
```

Expected lanes: logs across candidate services and code. Add database evidence to compare final states when necessary.

## 6. Approximate time and user description only

```text
Environment: test
Approximate time: around 16:20 local time
Reporter: QA
Observed: The checkout page said the reservation could not be completed.
```

Expected lanes: resolve candidate services first, then a bounded log search for the strongest candidate. Ask for time zone, user-safe business identifier, or API only if the search cannot be bounded safely.
