#!/usr/bin/env python3
"""Validate the Fast/Deep structure and scope controls."""

from __future__ import annotations

import importlib.util
import json
import re
import sys
import tempfile
from pathlib import Path


SKILL = Path(".github/skills/runtime-incident-investigation")
REQUIRED = [
    Path("README.md"), Path("incident-investigation.code-workspace"),
    Path(".github/agents/runtime-incident-fast.agent.md"),
    Path(".github/agents/runtime-incident-deep.agent.md"),
    Path(".github/agents/log-evidence.agent.md"),
    Path(".github/agents/database-evidence.agent.md"),
    Path(".github/prompts/investigate-runtime-fast.prompt.md"),
    SKILL / "SKILL.md", SKILL / "resources/fast-workflow.md",
    SKILL / "assets/fast-report.template.md",
    SKILL / "scripts/scoped_code_search.py",
    SKILL / "tests/test_scoped_code_search.py",
]


def root() -> Path:
    return Path(__file__).resolve().parents[4]


def header(text: str) -> str:
    match = re.match(r"\A---\s*\n(.*?)\n---\s*(?:\n|\Z)", text, re.DOTALL)
    return match.group(1) if match else ""


def check_fast(base: Path, errors: list[str]) -> None:
    path = base / ".github/agents/runtime-incident-fast.agent.md"
    text = path.read_text(encoding="utf-8")
    frontmatter = header(text)
    tools = re.search(r"(?ms)^tools:\s*\n(.*?)(?=^[A-Za-z][\w-]*:|\Z)", frontmatter)
    tool_names = set(re.findall(r"(?m)^\s*-\s+(.+?)\s*$", tools.group(1))) if tools else set()
    if "search" in tool_names:
        errors.append("Fast agent contains the prohibited search tool")
    if "agent" in tool_names:
        errors.append("Fast agent contains the prohibited agent tool")
    if not re.search(r"(?m)^agents:\s*\[\]\s*$", frontmatter):
        errors.append("Fast agent must allow no subagents with agents: []")
    budget_phrases = ("12 total tool calls", "3 scoped code searches", "5 source files read", "3 log searches", "2 bounded SQL queries", "1 primary service")
    for phrase in budget_phrases:
        if phrase.casefold() not in text.casefold():
            errors.append(f"Fast agent lacks explicit budget: {phrase}")
    if "hard service boundary" not in text.casefold():
        errors.append("Fast agent lacks a hard service boundary")
    if "PreToolUse:" not in frontmatter or "guard_read_only.py" not in frontmatter:
        errors.append("Fast agent lacks its execution PreToolUse hook")
    if "completed-report-example" in text or "investigation-report.template" in text:
        errors.append("Fast agent links to Deep completed-report or sixteen-section material")


def check_deep(base: Path, errors: list[str]) -> None:
    text = (base / ".github/agents/runtime-incident-deep.agent.md").read_text(encoding="utf-8")
    frontmatter = header(text)
    if "Code Evidence" in frontmatter or "Incident Synthesizer" in frontmatter:
        errors.append("Deep agent exposes a removed automatic subagent path")
    for name in ("Log Evidence", "Database Evidence"):
        if f"  - {name}" not in frontmatter:
            errors.append(f"Deep agent does not expose {name}")
    if "at most two evidence subagents" not in text.casefold():
        errors.append("Deep agent lacks the two-subagent limit")


def check_search_behavior(base: Path, errors: list[str]) -> None:
    script = base / SKILL / "scripts/scoped_code_search.py"
    spec = importlib.util.spec_from_file_location("self_check_scoped", script)
    if not spec or not spec.loader:
        errors.append("Cannot load scoped search script")
        return
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    with tempfile.TemporaryDirectory() as temporary:
        temp = Path(temporary)
        repository = temp / "configured"
        sibling = temp / "sibling"
        (repository / "src/main/java").mkdir(parents=True)
        (sibling / "src/main/java").mkdir(parents=True)
        (repository / "src/main/java/App.java").write_text("safe", encoding="utf-8")
        catalog = temp / "catalog.json"
        catalog.write_text(json.dumps({"services": [{"name": "safe", "aliases": [], "localRepositoryPath": str(repository)}]}), encoding="utf-8")
        try:
            module.search(catalog, str(sibling), "safe")
            errors.append("Scoped search accepts an unconfigured arbitrary path")
        except module.SearchError:
            pass
        try:
            module.search(catalog, "safe", "safe", subdirs=["src/main/java/../../../../sibling"])
            errors.append("Scoped search permits traversal outside its configured repository")
        except module.SearchError:
            pass


def main() -> int:
    base = root()
    errors: list[str] = []
    for relative in REQUIRED:
        if not (base / relative).is_file():
            errors.append(f"Missing required file: {relative}")
    removed = ["runtime-incident-investigator.agent.md", "code-evidence.agent.md", "incident-synthesizer.agent.md"]
    for filename in removed:
        if (base / ".github/agents" / filename).exists():
            errors.append(f"Obsolete automatic agent remains: {filename}")
    try:
        workspace = json.loads((base / "incident-investigation.code-workspace").read_text(encoding="utf-8"))
        folders = workspace.get("folders", [])
        if len(folders) > 2 or sum("TODO_REPLACE_SERVICE_" in str(item) for item in folders) >= 6:
            errors.append("Workspace contains six active service folders")
    except (OSError, json.JSONDecodeError) as exc:
        errors.append(f"Workspace JSON is invalid: {exc}")
    if all((base / item).is_file() for item in REQUIRED):
        check_fast(base, errors)
        check_deep(base, errors)
        check_search_behavior(base, errors)
    for path in (base / SKILL / "scripts").glob("*.py"):
        try:
            compile(path.read_text(encoding="utf-8"), str(path), "exec")
        except (OSError, SyntaxError) as exc:
            errors.append(f"Python compilation failed for {path.name}: {exc}")
    if errors:
        print(f"Self-check failed with {len(errors)} error(s):", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(f"OK: Fast/Deep investigation kit passed {len(REQUIRED)} required-file and policy checks.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
