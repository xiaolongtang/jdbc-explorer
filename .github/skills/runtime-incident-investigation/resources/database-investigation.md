# Database Investigation

Use environment databases only through `jdbc-explorer` and only after validating the service catalog.

## Prepare the query

1. Confirm the environment and connection alias from the selected service.
2. Confirm schema and table ownership. Do not assume the selected service wrote a row merely because it owns or reads the table.
3. State the query purpose separately from the SQL.
4. Select only required, non-sensitive columns.
5. Filter by trace ID, business ID, timestamp range, or another known indexed key.
6. Add a bounded result limit appropriate to the database dialect.
7. Avoid full table scans, broad wildcards, unbounded joins, and unnecessary related rows.

## Allowed SQL

Execute one statement at a time. Allow only:

- A single `SELECT`.
- A read-only `WITH ... SELECT`.

Never use `SELECT FOR UPDATE`, DML, DDL, transaction control, stored procedures, anonymous blocks, administrative commands, or multiple statements.

Join related tables only when the catalog or inspected code supports the relationship. Qualify ambiguous columns and preserve time-zone semantics. For state transitions, order by a known timestamp and stable secondary key.

## Evidence output

Every `DB-NNN` record must contain:

- Connection alias, schema, table, and environment.
- Query purpose and bounded redacted SQL.
- Query execution timestamp and relevant database time zone.
- Minimal returned fields or aggregate result.
- Observation, interpretation, hypothesis effect, confidence, limitations, and what the record does not prove.

Redact configured sensitive columns and unnecessary business values. An empty result means only that this bounded query returned no rows at execution time; it does not prove the data never existed. Database state alone does not identify the writer.
