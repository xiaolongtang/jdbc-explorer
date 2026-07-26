#!/usr/bin/env python3
"""Block unsafe JDBC and SSH tool calls before execution."""

from __future__ import annotations

import json
import re
import shlex
import sys
from pathlib import PurePosixPath
from typing import Any


SQL_KEYS = {"sql", "query", "statement"}
COMMAND_KEYS = {"command", "cmd", "shellcommand"}
SSH_ALLOWED_COMMANDS = {
    "awk",
    "bzcat",
    "bzip2",
    "cat",
    "date",
    "find",
    "grep",
    "gunzip",
    "gzip",
    "head",
    "ls",
    "sed",
    "stat",
    "tail",
    "wc",
    "xz",
    "xzcat",
    "zcat",
    "zgrep",
}
SSH_SAFE_TOOL_MARKERS = {
    "cat",
    "date",
    "find",
    "grep",
    "head",
    "list",
    "read",
    "search",
    "stat",
    "tail",
}
SSH_MUTATION_MARKERS = {
    "chmod",
    "chown",
    "copy",
    "cp",
    "delete",
    "deploy",
    "install",
    "kill",
    "move",
    "mv",
    "package",
    "pkill",
    "remove",
    "restart",
    "rm",
    "service",
    "stop",
    "tee",
    "touch",
    "upload",
    "write",
}
SQL_FORBIDDEN_PATTERN = re.compile(
    r"\b("
    r"alter|analyze|begin|call|commit|copy|create|delete|do|drop|execute|"
    r"exec|grant|insert|lock|merge|refresh|replace|revoke|rollback|"
    r"truncate|update|upsert|vacuum"
    r")\b",
    re.IGNORECASE,
)


def response(decision: str, reason: str) -> dict[str, Any]:
    return {
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": decision,
            "permissionDecisionReason": reason,
        }
    }


def normalized_key(value: str) -> str:
    return re.sub(r"[^a-z0-9]", "", value.casefold())


def collect_named_strings(
    value: Any, accepted_keys: set[str], results: list[str]
) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if normalized_key(str(key)) in accepted_keys and isinstance(child, str):
                results.append(child)
            else:
                collect_named_strings(child, accepted_keys, results)
    elif isinstance(value, list):
        for child in value:
            collect_named_strings(child, accepted_keys, results)


def mask_sql(sql: str) -> str | None:
    output: list[str] = []
    index = 0
    state = "normal"
    while index < len(sql):
        char = sql[index]
        next_char = sql[index + 1] if index + 1 < len(sql) else ""

        if state == "normal":
            if char == "-" and next_char == "-":
                output.extend((" ", " "))
                index += 2
                state = "line-comment"
                continue
            if char == "/" and next_char == "*":
                output.extend((" ", " "))
                index += 2
                state = "block-comment"
                continue
            if char == "'":
                output.append(" ")
                index += 1
                state = "single-quote"
                continue
            if char == '"':
                output.append(" ")
                index += 1
                state = "double-quote"
                continue
            output.append(char)
            index += 1
            continue

        if state == "line-comment":
            output.append("\n" if char == "\n" else " ")
            index += 1
            if char == "\n":
                state = "normal"
            continue

        if state == "block-comment":
            if char == "*" and next_char == "/":
                output.extend((" ", " "))
                index += 2
                state = "normal"
            else:
                output.append(" ")
                index += 1
            continue

        quote_char = "'" if state == "single-quote" else '"'
        if char == quote_char and next_char == quote_char:
            output.extend((" ", " "))
            index += 2
        elif char == quote_char:
            output.append(" ")
            index += 1
            state = "normal"
        else:
            output.append(" ")
            index += 1

    if state in {"block-comment", "single-quote", "double-quote"}:
        return None
    return "".join(output)


def validate_sql(sql: str) -> tuple[bool, str]:
    if not sql.strip():
        return False, "JDBC operation denied because the SQL statement is empty."

    masked = mask_sql(sql)
    if masked is None:
        return False, "JDBC operation denied because the SQL is syntactically ambiguous."

    statement = masked.strip()
    semicolons = [index for index, char in enumerate(statement) if char == ";"]
    if semicolons:
        if len(semicolons) != 1 or semicolons[0] != len(statement) - 1:
            return False, "JDBC operation denied because multiple statements are not allowed."
        statement = statement[:-1].rstrip()

    if not statement:
        return False, "JDBC operation denied because no statement was found."
    if re.search(r"\bfor\s+update\b", statement, re.IGNORECASE):
        return False, "JDBC operation denied because SELECT FOR UPDATE is not read-only."
    if SQL_FORBIDDEN_PATTERN.search(statement):
        return False, "JDBC operation denied because a mutating or administrative keyword was found."
    if re.search(
        r"\b(nextval|setval|pg_advisory_lock|pg_try_advisory_lock)\s*\(",
        statement,
        re.IGNORECASE,
    ):
        return False, "JDBC operation denied because a state-changing function was found."
    if re.search(r"\bselect\s+.+\s+into\b", statement, re.IGNORECASE | re.DOTALL):
        return False, "JDBC operation denied because SELECT INTO may write data."

    first_word = re.match(r"^[A-Za-z]+", statement)
    if first_word is None or first_word.group(0).casefold() not in {"select", "with"}:
        return False, "JDBC operation denied; only SELECT or read-only WITH ... SELECT is allowed."
    if first_word.group(0).casefold() == "with" and not re.search(
        r"\bselect\b", statement, re.IGNORECASE
    ):
        return False, "JDBC operation denied because the WITH statement has no SELECT."

    return True, "Read-only single-statement SQL allowed."


def has_shell_operator(command: str) -> bool:
    quote: str | None = None
    escaped = False
    index = 0
    while index < len(command):
        char = command[index]
        next_char = command[index + 1] if index + 1 < len(command) else ""
        if escaped:
            escaped = False
            index += 1
            continue
        if char == "\\" and quote != "'":
            escaped = True
            index += 1
            continue
        if quote == "'":
            if char == "'":
                quote = None
            index += 1
            continue
        if quote == '"':
            if char == '"':
                quote = None
            elif char == "`" or (char == "$" and next_char == "("):
                return True
            index += 1
            continue
        if char in {"'", '"'}:
            quote = char
            index += 1
            continue
        if char in {"\n", "\r", ";", "|", ">", "<", "`"}:
            return True
        if char == "&" and next_char == "&":
            return True
        if char == "$" and next_char == "(":
            return True
        index += 1
    return quote is not None or escaped


def option_present(tokens: list[str], short: str, long: str) -> bool:
    for token in tokens[1:]:
        if token == long or token.startswith(f"{long}="):
            return True
        if token.startswith("-") and not token.startswith("--") and short in token[1:]:
            return True
    return False


def validate_ssh_command(command: str) -> tuple[bool, str]:
    if not command.strip():
        return False, "SSH operation denied because the command is empty."
    if has_shell_operator(command):
        return False, "SSH operation denied because shell composition or redirection is not allowed."
    try:
        tokens = shlex.split(command, posix=True)
    except ValueError:
        return False, "SSH operation denied because the command is syntactically ambiguous."
    if not tokens:
        return False, "SSH operation denied because no command was found."

    executable = PurePosixPath(tokens[0]).name.casefold()
    if executable not in SSH_ALLOWED_COMMANDS:
        return False, f"SSH operation denied because {executable!r} is not an allowed read command."

    lowered_tokens = [token.casefold() for token in tokens[1:]]
    if executable == "sed":
        if option_present(tokens, "i", "--in-place"):
            return False, "SSH operation denied because sed in-place editing is not allowed."
        scripts = [token for token in tokens[1:] if not token.startswith("-")]
        if any(
            re.search(r"(^|[;{}])\s*[ew]\b", script, re.IGNORECASE)
            or re.search(r"s(.).*?\1.*?\1[^;]*[ew]\b", script, re.IGNORECASE)
            for script in scripts
        ):
            return False, "SSH operation denied because the sed program may write or execute."

    if executable == "find" and any(
        token in {
            "-delete",
            "-exec",
            "-execdir",
            "-fls",
            "-fprint",
            "-fprintf",
            "-ok",
            "-okdir",
        }
        for token in lowered_tokens
    ):
        return False, "SSH operation denied because the find action may mutate or write."

    if executable == "awk" and any(
        re.search(r"\bsystem\s*\(", token, re.IGNORECASE)
        or re.search(r"\bgetline\b", token, re.IGNORECASE)
        or re.search(r"\bprint\b.*>>?", token, re.IGNORECASE)
        for token in tokens[1:]
    ):
        return False, "SSH operation denied because the awk program may execute or write."

    if executable == "date" and (
        option_present(tokens, "s", "--set") or "--file" in lowered_tokens
    ):
        return False, "SSH operation denied because changing system time is not allowed."

    if executable == "tail" and option_present(tokens, "f", "--follow"):
        return False, "SSH operation denied because unbounded follow mode is not allowed."

    if executable in {"gzip", "gunzip", "bzip2", "xz"}:
        writes_stdout = (
            option_present(tokens, "c", "--stdout")
            or "--to-stdout" in lowered_tokens
        )
        if not writes_stdout:
            return False, "SSH decompression is allowed only when output is sent to stdout."

    return True, "Bounded read-only SSH command allowed."


def tool_leaf(tool_name: str) -> str:
    parts = re.split(r"[/.:]", tool_name.casefold())
    return normalized_key(parts[-1] if parts else tool_name)


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, OSError):
        print(json.dumps(response("deny", "Tool operation denied because hook input is invalid.")))
        return 0

    if not isinstance(payload, dict):
        print(json.dumps(response("deny", "Tool operation denied because hook input is ambiguous.")))
        return 0

    tool_name = str(payload.get("tool_name", ""))
    tool_input = payload.get("tool_input", {})
    lowered_name = tool_name.casefold()
    is_jdbc = "jdbc-explorer" in lowered_name or "jdbc" in lowered_name
    is_ssh = "ssh-mcp-server" in lowered_name or "ssh" in lowered_name

    if is_jdbc:
        statements: list[str] = []
        collect_named_strings(tool_input, SQL_KEYS, statements)
        if len(statements) != 1:
            result = response(
                "deny",
                "JDBC operation denied because exactly one explicit SQL statement is required.",
            )
        else:
            allowed, reason = validate_sql(statements[0])
            result = response("allow" if allowed else "deny", reason)
    elif is_ssh:
        leaf = tool_leaf(tool_name)
        if any(marker in leaf for marker in SSH_MUTATION_MARKERS):
            result = response(
                "deny",
                "SSH operation denied because the tool name indicates a mutating action.",
            )
        else:
            commands: list[str] = []
            collect_named_strings(tool_input, COMMAND_KEYS, commands)
            if len(commands) == 1:
                allowed, reason = validate_ssh_command(commands[0])
                result = response("allow" if allowed else "deny", reason)
            elif not commands and any(marker in leaf for marker in SSH_SAFE_TOOL_MARKERS):
                result = response("allow", "Server-native read-only SSH tool allowed.")
            else:
                result = response(
                    "deny",
                    "SSH operation denied because its read-only behavior cannot be established.",
                )
    else:
        result = response("allow", "Non-SSH and non-JDBC tool is outside this guard's scope.")

    print(json.dumps(result))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
