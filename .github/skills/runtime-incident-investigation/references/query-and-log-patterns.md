# Query and Log Patterns

Replace placeholders with validated catalog values. Keep paths quoted, searches bounded, and outputs minimal. Do not combine commands with pipes or redirection when the SSH guard is active; prefer a server-native search tool when one command cannot express the bound safely.

## Log searches

Trace ID in a current log:

```text
grep -n -m 50 -C 3 "abc-123" /var/log/example-service/application.log
```

Request or correlation ID:

```text
grep -n -m 50 -C 2 "corr-908" /var/log/example-service/application.log
```

Compressed archived log:

```text
zgrep -n -m 50 -C 2 "abc-123" /var/log/example-service/archive/application-2026-07-25.log.gz
```

Bounded timestamp prefix:

```text
grep -n -m 100 "2026-07-25T14:0" /var/log/example-service/application.log
```

Business ID:

```text
grep -n -m 50 -C 2 "ORD-10023" /var/log/example-service/application.log
```

Record the configured log time zone. Search an adjacent bounded window if clock skew is plausible; do not search all retained logs.

## Bounded SQL

Use the database dialect's supported limit form. Select only needed columns.

Find one business record:

```sql
SELECT order_id, status, updated_at, version
FROM app_order
WHERE order_id = 'ORD-10023'
FETCH FIRST 10 ROWS ONLY
```

Filter a time window and trace:

```sql
SELECT event_id, order_id, event_type, created_at
FROM order_event
WHERE trace_id = 'abc-123'
  AND created_at >= TIMESTAMP '2026-07-25 05:55:00'
  AND created_at < TIMESTAMP '2026-07-25 06:10:00'
ORDER BY created_at, event_id
FETCH FIRST 100 ROWS ONLY
```

Join related tables carefully:

```sql
SELECT o.order_id, o.status, e.event_type, e.created_at
FROM app_order o
JOIN order_event e ON e.order_id = o.order_id
WHERE o.order_id = 'ORD-10023'
ORDER BY e.created_at, e.event_id
FETCH FIRST 100 ROWS ONLY
```

Verify state transitions:

```sql
SELECT event_type, previous_status, new_status, created_at
FROM order_status_history
WHERE order_id = 'ORD-10023'
ORDER BY created_at, history_id
FETCH FIRST 100 ROWS ONLY
```

Check duplicates:

```sql
SELECT external_reference, COUNT(*) AS record_count
FROM payment_record
WHERE external_reference = 'PAY-REDACTED'
GROUP BY external_reference
HAVING COUNT(*) > 1
FETCH FIRST 10 ROWS ONLY
```

Compare timestamps and time zones:

```sql
SELECT event_id, created_at, updated_at
FROM order_event
WHERE order_id = 'ORD-10023'
  AND created_at >= TIMESTAMP '2026-07-25 05:55:00'
  AND created_at < TIMESTAMP '2026-07-25 06:10:00'
ORDER BY created_at, event_id
FETCH FIRST 100 ROWS ONLY
```

State the assumed database time zone separately. Never add DML, DDL, `FOR UPDATE`, stored procedures, or multiple statements.
