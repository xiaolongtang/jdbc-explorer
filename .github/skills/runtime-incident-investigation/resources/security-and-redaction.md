# Security and Redaction

## Read-only policy

Investigation must not modify source code, server files, services, containers, infrastructure, configuration, or database data. Evidence agents have no editing tools. Fast mode has direct, guarded read-only SSH and JDBC tools; Deep delegates those lanes to its guarded evidence agents. Hooks deny unsafe MCP operations, but server accounts and database grants must also be read-only.

When an operation is blocked, report the requested purpose, the policy category, and a safe read-only alternative. Do not bypass, encode, split, or obscure a denied command.

## Credentials and secrets

Never store or reproduce passwords, tokens, cookies, authorization headers, access keys, private keys, connection strings containing credentials, or secret environment values. The catalog contains aliases only. If a source exposes a secret, redact it immediately and note the redaction.

## PII and business data

Minimize exposure. Select only fields needed to test the hypothesis. Redact email addresses, phone numbers, personal names when unnecessary, addresses, account identifiers, payment data, sensitive catalog columns, and unrelated business values. Preserve only a safe suffix or stable placeholder when correlation requires it.

Do not copy complete database rows when a subset is enough. Do not dump complete logs. Prefer counts, existence checks, bounded timestamps, state fields, and redacted identifiers.

## Audit and retention

Do not create audit logs containing raw prompts, MCP arguments, database results, log contents, or unredacted evidence. Validation scripts may report file paths and policy categories but must not print protected values.

## Reporting

Evidence records must list redactions and access limitations. A blocked operation or inaccessible source is a gap, not evidence for a hypothesis. Lower the conclusion status when the missing source affects a material causal link.
