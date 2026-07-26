#!/usr/bin/env python3
"""Validate a runtime incident investigation service catalog."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any


SERVICE_FIELDS = {
    "name",
    "aliases",
    "workspaceFolder",
    "localRepositoryPath",
    "apiPatterns",
    "errorPatterns",
    "ownedTables",
    "logs",
    "database",
    "downstreamServices",
}
LOG_FIELDS = {
    "sshTarget",
    "currentLogPath",
    "archiveLogPath",
    "archiveGlob",
    "timezone",
}
DATABASE_FIELDS = {"connection", "schemas", "tables"}
TABLE_FIELDS = {
    "name",
    "businessKeyColumns",
    "traceColumns",
    "timeColumns",
    "sensitiveColumns",
}
CREDENTIAL_KEY_PARTS = (
    "password",
    "token",
    "secret",
    "privatekey",
    "jdbcurlwithpassword",
    "accesskey",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate JSON structure and safety rules for a service catalog."
    )
    parser.add_argument("catalog", type=Path, help="Path to the catalog JSON file.")
    return parser.parse_args()


def normalized_key(value: str) -> str:
    return re.sub(r"[^a-z0-9]", "", value.casefold())


def find_credential_keys(value: Any, path: str, errors: list[str]) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            key_text = str(key)
            normalized = normalized_key(key_text)
            if any(part in normalized for part in CREDENTIAL_KEY_PARTS):
                errors.append(f"{path}.{key_text}: credential-like fields are prohibited")
            find_credential_keys(child, f"{path}.{key_text}", errors)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            find_credential_keys(child, f"{path}[{index}]", errors)


def require_object(value: Any, path: str, errors: list[str]) -> dict[str, Any] | None:
    if not isinstance(value, dict):
        errors.append(f"{path}: expected an object")
        return None
    return value


def require_nonempty_string(
    value: Any, path: str, errors: list[str]
) -> str | None:
    if not isinstance(value, str) or not value.strip():
        errors.append(f"{path}: expected a non-empty string")
        return None
    return value.strip()


def require_string_list(
    value: Any,
    path: str,
    errors: list[str],
    *,
    allow_empty: bool = True,
) -> list[str] | None:
    if not isinstance(value, list):
        errors.append(f"{path}: expected an array")
        return None
    if not allow_empty and not value:
        errors.append(f"{path}: expected at least one item")
    for index, item in enumerate(value):
        require_nonempty_string(item, f"{path}[{index}]", errors)
    return value


def require_fields(
    value: dict[str, Any], fields: set[str], path: str, errors: list[str]
) -> None:
    for field in sorted(fields):
        if field not in value:
            errors.append(f"{path}.{field}: required field is missing")


def validate_log_environment(
    value: Any, path: str, errors: list[str]
) -> None:
    mapping = require_object(value, path, errors)
    if mapping is None:
        return
    require_fields(mapping, LOG_FIELDS, path, errors)
    for field in sorted(LOG_FIELDS):
        if field in mapping:
            require_nonempty_string(mapping[field], f"{path}.{field}", errors)


def validate_table(value: Any, path: str, errors: list[str]) -> None:
    table = require_object(value, path, errors)
    if table is None:
        return
    require_fields(table, TABLE_FIELDS, path, errors)
    if "name" in table:
        require_nonempty_string(table["name"], f"{path}.name", errors)
    for field in sorted(TABLE_FIELDS - {"name"}):
        if field in table:
            require_string_list(table[field], f"{path}.{field}", errors)


def validate_database_environment(
    value: Any, path: str, errors: list[str]
) -> None:
    mapping = require_object(value, path, errors)
    if mapping is None:
        return
    require_fields(mapping, DATABASE_FIELDS, path, errors)
    if "connection" in mapping:
        require_nonempty_string(mapping["connection"], f"{path}.connection", errors)
    if "schemas" in mapping:
        require_string_list(
            mapping["schemas"], f"{path}.schemas", errors, allow_empty=False
        )
    tables = mapping.get("tables")
    if not isinstance(tables, list):
        errors.append(f"{path}.tables: expected an array")
    else:
        if not tables:
            errors.append(f"{path}.tables: expected at least one table")
        for index, table in enumerate(tables):
            validate_table(table, f"{path}.tables[{index}]", errors)


def validate_service(
    value: Any, index: int, errors: list[str]
) -> tuple[str | None, list[str]]:
    path = f"$.services[{index}]"
    service = require_object(value, path, errors)
    if service is None:
        return None, []
    require_fields(service, SERVICE_FIELDS, path, errors)

    name = None
    if "name" in service:
        name = require_nonempty_string(service["name"], f"{path}.name", errors)

    for field in ("aliases", "apiPatterns", "errorPatterns", "ownedTables"):
        if field in service:
            require_string_list(
                service[field], f"{path}.{field}", errors, allow_empty=False
            )
    if "downstreamServices" in service:
        require_string_list(
            service["downstreamServices"],
            f"{path}.downstreamServices",
            errors,
        )
    for field in ("workspaceFolder", "localRepositoryPath"):
        if field in service:
            require_nonempty_string(service[field], f"{path}.{field}", errors)

    logs = service.get("logs")
    databases = service.get("database")
    log_mapping = require_object(logs, f"{path}.logs", errors)
    database_mapping = require_object(databases, f"{path}.database", errors)
    log_environments: set[str] = set()
    database_environments: set[str] = set()

    if log_mapping is not None:
        if not log_mapping:
            errors.append(f"{path}.logs: expected at least one environment")
        for environment, config in log_mapping.items():
            require_nonempty_string(environment, f"{path}.logs environment", errors)
            log_environments.add(str(environment))
            validate_log_environment(config, f"{path}.logs.{environment}", errors)

    if database_mapping is not None:
        if not database_mapping:
            errors.append(f"{path}.database: expected at least one environment")
        for environment, config in database_mapping.items():
            require_nonempty_string(
                environment, f"{path}.database environment", errors
            )
            database_environments.add(str(environment))
            validate_database_environment(
                config, f"{path}.database.{environment}", errors
            )

    if (
        log_mapping is not None
        and database_mapping is not None
        and log_environments != database_environments
    ):
        missing_logs = sorted(database_environments - log_environments)
        missing_databases = sorted(log_environments - database_environments)
        if missing_logs:
            errors.append(
                f"{path}.logs: missing environment mappings: {', '.join(missing_logs)}"
            )
        if missing_databases:
            errors.append(
                f"{path}.database: missing environment mappings: "
                f"{', '.join(missing_databases)}"
            )

    downstream = service.get("downstreamServices")
    return name, downstream if isinstance(downstream, list) else []


def validate_catalog(data: Any) -> list[str]:
    errors: list[str] = []
    root = require_object(data, "$", errors)
    if root is None:
        return errors

    find_credential_keys(root, "$", errors)

    if root.get("catalogVersion") != 1:
        errors.append("$.catalogVersion: expected integer value 1")

    services = root.get("services")
    if not isinstance(services, list):
        errors.append("$.services: expected an array")
        return errors
    if not services:
        errors.append("$.services: expected at least one service")

    names: dict[str, int] = {}
    downstream_by_service: list[tuple[str | None, list[str]]] = []
    for index, service in enumerate(services):
        name, downstream = validate_service(service, index, errors)
        downstream_by_service.append((name, downstream))
        if name:
            folded = name.casefold()
            if folded in names:
                errors.append(
                    f"$.services[{index}].name: duplicate of "
                    f"$.services[{names[folded]}].name"
                )
            else:
                names[folded] = index

    for index, (name, downstream) in enumerate(downstream_by_service):
        for downstream_index, target in enumerate(downstream):
            if (
                isinstance(target, str)
                and not target.startswith("TODO_REPLACE_")
                and target.casefold() not in names
            ):
                errors.append(
                    f"$.services[{index}].downstreamServices[{downstream_index}]: "
                    f"unknown service {target!r}"
                )
            if name and isinstance(target, str) and target.casefold() == name.casefold():
                errors.append(
                    f"$.services[{index}].downstreamServices[{downstream_index}]: "
                    "a service cannot depend on itself"
                )

    return errors


def main() -> int:
    args = parse_args()
    try:
        with args.catalog.open("r", encoding="utf-8") as handle:
            data = json.load(handle)
    except FileNotFoundError:
        print(f"ERROR: catalog not found: {args.catalog}", file=sys.stderr)
        return 2
    except json.JSONDecodeError as exc:
        print(
            f"ERROR: invalid JSON at line {exc.lineno}, column {exc.colno}: {exc.msg}",
            file=sys.stderr,
        )
        return 2
    except OSError as exc:
        print(f"ERROR: cannot read catalog: {exc}", file=sys.stderr)
        return 2

    errors = validate_catalog(data)
    if errors:
        print(f"Catalog validation failed with {len(errors)} error(s):", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1

    service_count = len(data["services"])
    environments = sorted(
        {
            environment
            for service in data["services"]
            for environment in service["logs"].keys()
        }
    )
    print(
        f"OK: {args.catalog} contains {service_count} valid service(s) "
        f"for environment(s): {', '.join(environments)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
