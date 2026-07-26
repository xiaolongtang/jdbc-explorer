# Log Investigation

Use remote logs only through `ssh-mcp-server` and only after validating the service catalog.

## Select the source

Confirm the service, environment, SSH target alias, log time zone, current log path, archive path, and archive glob from the catalog. Select current logs when the incident falls within their retained period. Select archived or compressed logs when the incident predates current retention. Report uncertain retention or rotation boundaries.

## Search sequence

1. Convert the incident window to the log time zone and state the conversion.
2. Start with the strongest identifier: trace ID, request ID, or correlation ID.
3. If necessary, use a business ID, exact API path, exception class or message, and bounded timestamp pattern.
4. Search only the configured files matching the incident period.
5. Return the smallest surrounding context that shows ordering, component, severity, and relevant identifiers.
6. Follow correlated identifiers into downstream candidate services only after an explicit scope expansion.
7. Reconstruct a timestamped timeline and record clock or ordering uncertainty.

Use `grep` for current text logs and `zgrep`, `gzip -cd`, `zcat`, `bzcat`, or `xzcat` for compatible archives when allowed by the MCP implementation. Keep every command read-only and bounded with precise paths and patterns.

## Evidence output

Every `LOG-NNN` record must contain:

- SSH target alias.
- Exact current or archive path.
- Log time zone and timestamps.
- Minimal relevant redacted lines.
- Search identifiers and bounded window.
- Observation, interpretation, hypothesis effect, confidence, limitations, and what the record does not prove.

Absence is scoped to the searched targets, paths, patterns, and window. It does not prove that an event did not occur.

Never dump complete logs, write or delete files, redirect output, restart a service, alter infrastructure, deploy, install packages, mutate containers, or bypass the read-only hook.
