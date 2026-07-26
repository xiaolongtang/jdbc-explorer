# MCP Tool Mapping

The checked-in custom agents expect these MCP server IDs:

- `ssh-mcp-server` for remote log access.
- `jdbc-explorer` for database access.

The agent declarations use `ssh-mcp-server/*` and `jdbc-explorer/*` because exact tool names differ between server implementations.

## Inspect registered tools in VS Code

1. Open the Chat view.
2. Select the relevant custom agent.
3. Use **Configure Tools** to inspect available MCP servers and tools.
4. If a tool is missing, open the Agent Customizations diagnostics view and review the reported server ID, tool prefix, and loading error.
5. Confirm the server is running and that its authentication is configured outside this repository.

## Replace a different local server ID

If the registered ID differs:

1. Replace `ssh-mcp-server/*` in `.github/agents/log-evidence.agent.md`, or replace `jdbc-explorer/*` in `.github/agents/database-evidence.agent.md`.
2. Update the server-name detection constants in `scripts/guard_read_only.py`.
3. Update expected identifiers in `scripts/self_check.py`.
4. Re-run `self_check.py` and test one safe operation plus one intentionally denied operation.

Do not add SSH or JDBC tools to the main orchestrator.

## Choose safe tools

The log agent should prefer server-native read, search, tail, and archive-read tools. If the server exposes a generic command tool, use one bounded command from the allowlist enforced by `guard_read_only.py`. Avoid shell composition and redirection.

The database agent should prefer a query tool that accepts a connection alias and one SQL statement. Use metadata tools only when they are read-only and required to qualify a bounded query. Query only the cataloged environment and connection.

Tool names and input field names vary. Inspect the registered schema rather than guessing. The guard recognizes common `sql`, `query`, `statement`, `command`, and `cmd` inputs and denies ambiguous SSH or JDBC operations by default.
