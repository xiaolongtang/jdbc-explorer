# Service Catalog Reference

The local catalog maps stable business and runtime signals to approved evidence sources. It contains aliases and paths, never credentials.

## Top-level structure

```json
{
  "catalogVersion": 1,
  "services": []
}
```

`services` is a non-empty array. Every service has a unique `name`.

## Service fields

| Field | Type | Purpose |
|---|---|---|
| `name` | string | Stable service identifier. |
| `aliases` | string array | Human, deployment, and repository aliases. |
| `workspaceFolder` | string | Folder name in the VS Code multi-root workspace. |
| `localRepositoryPath` | string | Local absolute or workspace-relative repository path. |
| `apiPatterns` | string array | Literal prefixes or glob patterns used for service resolution. |
| `errorPatterns` | string array | Stable exception names or error fragments. |
| `ownedTables` | string array | Qualified or unqualified table names associated with the service. |
| `logs` | object keyed by environment | Approved SSH aliases, paths, archive globs, and log time zones. |
| `database` | object keyed by environment | Approved connection aliases, schemas, and table metadata. |
| `downstreamServices` | string array | Names of catalog services called by this service. |

## Log environment

Each `logs.<environment>` object contains:

- `sshTarget`: an alias registered with `ssh-mcp-server`.
- `currentLogPath`: exact current log file or a narrowly scoped configured path.
- `archiveLogPath`: directory containing rotated logs.
- `archiveGlob`: bounded archive pattern.
- `timezone`: IANA time-zone name such as `Asia/Shanghai` or `UTC`.

## Database environment

Each `database.<environment>` object contains:

- `connection`: a connection alias registered with `jdbc-explorer`, never a JDBC URL with credentials.
- `schemas`: allowed schema names.
- `tables`: table metadata used to build bounded queries.

Each table object contains `name`, `businessKeyColumns`, `traceColumns`, `timeColumns`, and `sensitiveColumns`.

## Environments

`test` and `staging` use the same schema. Add another environment by adding the same key under both `logs` and `database`; no schema change is required. Keep environment names consistent for a service.

## Local configuration

Copy values from operational documentation into `service-catalog.local.json` and replace all `TODO_REPLACE_*` placeholders. The local file is ignored by Git because paths and infrastructure aliases may be company-specific. Validate it after every change.

Never add fields named `password`, `token`, `secret`, `privateKey`, `jdbcUrlWithPassword`, `accessKey`, or equivalent credential-bearing variants.
