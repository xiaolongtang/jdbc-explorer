#!/usr/bin/env python3
"""Rank candidate services from bounded incident signals."""

from __future__ import annotations

import argparse
import fnmatch
import json
import sys
from pathlib import Path
from typing import Any

from validate_service_catalog import validate_catalog


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Rank service catalog entries without silently choosing weak matches."
    )
    parser.add_argument("catalog", type=Path, help="Validated service catalog JSON.")
    parser.add_argument("--api-path", default="", help="Reported API path.")
    parser.add_argument("--service-hint", default="", help="Service name or alias hint.")
    parser.add_argument("--error-text", default="", help="Exception or error text.")
    parser.add_argument("--table-name", default="", help="Qualified or unqualified table.")
    parser.add_argument("--log-text", default="", help="Small relevant log fragment.")
    parser.add_argument(
        "--workspace-folder", default="", help="Workspace folder name or path."
    )
    return parser.parse_args()


def normalized(value: str) -> str:
    return value.strip().casefold()


def api_matches(path: str, pattern: str) -> bool:
    path_value = path.split("?", 1)[0].strip()
    pattern_value = pattern.strip()
    if not path_value or not pattern_value:
        return False
    if fnmatch.fnmatchcase(path_value.casefold(), pattern_value.casefold()):
        return True
    if pattern_value.endswith("/**"):
        return path_value.casefold().startswith(pattern_value[:-3].casefold())
    return path_value.casefold() == pattern_value.casefold()


def table_matches(candidate: str, requested: str) -> bool:
    left = normalized(candidate)
    right = normalized(requested)
    return bool(left and right and (left == right or left.rsplit(".", 1)[-1] == right.rsplit(".", 1)[-1]))


def add_match(
    matches: list[dict[str, Any]], signal: str, match: str, points: int
) -> None:
    matches.append({"signal": signal, "match": match, "points": points})


def score_service(service: dict[str, Any], args: argparse.Namespace) -> dict[str, Any]:
    matches: list[dict[str, Any]] = []
    name = str(service["name"])
    aliases = [str(value) for value in service["aliases"]]
    workspace = str(service["workspaceFolder"])
    repository = str(service["localRepositoryPath"])

    hint = normalized(args.service_hint)
    if hint:
        if hint == normalized(name):
            add_match(matches, "exactServiceName", name, 120)
        else:
            exact_alias = next(
                (alias for alias in aliases if normalized(alias) == hint), None
            )
            if exact_alias:
                add_match(matches, "exactAlias", exact_alias, 100)
            elif hint in normalized(name) or hint in normalized(workspace):
                add_match(matches, "partialServiceHint", workspace, 35)

    workspace_hint = normalized(args.workspace_folder)
    if workspace_hint:
        if workspace_hint == normalized(workspace):
            add_match(matches, "workspaceFolder", workspace, 80)
        elif workspace_hint in normalized(repository) or normalized(workspace) in workspace_hint:
            add_match(matches, "workspacePath", workspace, 60)

    if args.api_path:
        api_pattern = next(
            (
                pattern
                for pattern in service["apiPatterns"]
                if api_matches(args.api_path, str(pattern))
            ),
            None,
        )
        if api_pattern:
            add_match(matches, "apiPattern", str(api_pattern), 70)

    error_text = normalized(args.error_text)
    if error_text:
        error_pattern = next(
            (
                str(pattern)
                for pattern in service["errorPatterns"]
                if normalized(str(pattern)) in error_text
                or error_text in normalized(str(pattern))
            ),
            None,
        )
        if error_pattern:
            add_match(matches, "errorPattern", error_pattern, 50)

    if args.table_name:
        table = next(
            (
                str(candidate)
                for candidate in service["ownedTables"]
                if table_matches(str(candidate), args.table_name)
            ),
            None,
        )
        if table:
            add_match(matches, "ownedTable", table, 70)

    log_text = normalized(args.log_text)
    if log_text:
        log_matches: list[tuple[str, str, int]] = []
        for value in [name, workspace, *aliases]:
            if normalized(value) and normalized(value) in log_text:
                log_matches.append(("logServiceIdentity", value, 30))
                break
        for value in service["errorPatterns"]:
            if normalized(str(value)) and normalized(str(value)) in log_text:
                log_matches.append(("logErrorPattern", str(value), 35))
                break
        for value in service["apiPatterns"]:
            literal = str(value).replace("**", "").replace("*", "").rstrip("/")
            if normalized(literal) and normalized(literal) in log_text:
                log_matches.append(("logApiPattern", str(value), 20))
                break
        for signal, match, points in log_matches:
            add_match(matches, signal, match, points)

    score = sum(int(item["points"]) for item in matches)
    return {
        "service": name,
        "score": score,
        "matchedSignals": matches,
        "explanation": (
            "; ".join(
                f"{item['signal']} matched {item['match']!r} (+{item['points']})"
                for item in matches
            )
            if matches
            else "No supplied signal matched this service."
        ),
    }


def classify(candidates: list[dict[str, Any]]) -> tuple[str, str, str | None]:
    positive = [candidate for candidate in candidates if candidate["score"] > 0]
    if not positive:
        return (
            "no_match",
            "No supplied signal matched the catalog. Collect a service alias, API path, "
            "error pattern, owned table, workspace folder, or bounded log fragment.",
            None,
        )

    top = positive[0]
    if top["score"] < 60:
        return (
            "low_confidence",
            f"The highest score is {top['score']}, below the minimum confidence threshold "
            "of 60. Do not select a service without another discriminating signal.",
            None,
        )

    if len(positive) > 1:
        second = positive[1]
        margin = top["score"] - second["score"]
        if margin < 20:
            return (
                "ambiguous",
                f"The top two candidates differ by only {margin} point(s). Collect a "
                "signal that distinguishes them before selecting a service.",
                None,
            )

    return (
        "resolved",
        "The leading candidate meets the confidence threshold and has a sufficient "
        "score margin.",
        str(top["service"]),
    )


def main() -> int:
    args = parse_args()
    try:
        data = json.loads(args.catalog.read_text(encoding="utf-8"))
    except FileNotFoundError:
        print(
            json.dumps(
                {"status": "error", "reason": f"Catalog not found: {args.catalog}"}
            )
        )
        return 2
    except (OSError, json.JSONDecodeError) as exc:
        print(json.dumps({"status": "error", "reason": f"Cannot read catalog: {exc}"}))
        return 2

    errors = validate_catalog(data)
    if errors:
        print(
            json.dumps(
                {
                    "status": "error",
                    "reason": "Catalog validation failed.",
                    "validationErrors": errors,
                },
                indent=2,
            )
        )
        return 2

    candidates = [score_service(service, args) for service in data["services"]]
    candidates.sort(key=lambda item: (-int(item["score"]), str(item["service"])))
    status, reason, selected = classify(candidates)
    result = {
        "status": status,
        "selectedService": selected,
        "reason": reason,
        "providedSignals": {
            "apiPath": bool(args.api_path),
            "serviceHint": bool(args.service_hint),
            "errorText": bool(args.error_text),
            "tableName": bool(args.table_name),
            "logText": bool(args.log_text),
            "workspaceFolder": bool(args.workspace_folder),
        },
        "rankedCandidates": candidates,
    }
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
