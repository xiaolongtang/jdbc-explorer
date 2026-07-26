#!/usr/bin/env python3
"""Run deterministic structural and safety checks for the investigation kit."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path


SKILL_RELATIVE = Path(".github/skills/runtime-incident-investigation")
REQUIRED_FILES = [
    Path("README.md"),
    Path(".gitignore"),
    Path("incident-investigation.code-workspace"),
    Path(".vscode/settings.json"),
    Path(".github/agents/runtime-incident-investigator.agent.md"),
    Path(".github/agents/code-evidence.agent.md"),
    Path(".github/agents/log-evidence.agent.md"),
    Path(".github/agents/database-evidence.agent.md"),
    Path(".github/agents/incident-synthesizer.agent.md"),
    Path(".github/hooks/incident-investigation.json"),
    SKILL_RELATIVE / "SKILL.md",
    SKILL_RELATIVE / "resources/workflow.md",
    SKILL_RELATIVE / "resources/routing-and-escalation.md",
    SKILL_RELATIVE / "resources/evidence-standard.md",
    SKILL_RELATIVE / "resources/code-investigation.md",
    SKILL_RELATIVE / "resources/log-investigation.md",
    SKILL_RELATIVE / "resources/database-investigation.md",
    SKILL_RELATIVE / "resources/synthesis-and-response.md",
    SKILL_RELATIVE / "resources/security-and-redaction.md",
    SKILL_RELATIVE / "references/service-catalog-reference.md",
    SKILL_RELATIVE / "references/mcp-tool-mapping.md",
    SKILL_RELATIVE / "references/incident-input-examples.md",
    SKILL_RELATIVE / "references/query-and-log-patterns.md",
    SKILL_RELATIVE / "references/completed-report-example.md",
    SKILL_RELATIVE / "assets/service-catalog.example.json",
    SKILL_RELATIVE / "assets/service-catalog.local.json",
    SKILL_RELATIVE / "assets/incident-request.template.json",
    SKILL_RELATIVE / "assets/evidence-record.schema.json",
    SKILL_RELATIVE / "assets/investigation-report.template.md",
    SKILL_RELATIVE / "assets/stakeholder-reply.template.md",
    SKILL_RELATIVE / "scripts/validate_service_catalog.py",
    SKILL_RELATIVE / "scripts/resolve_service.py",
    SKILL_RELATIVE / "scripts/guard_read_only.py",
    SKILL_RELATIVE / "scripts/inject_evidence_contract.py",
    SKILL_RELATIVE / "scripts/redact_text.py",
    SKILL_RELATIVE / "scripts/self_check.py",
]
AGENT_EXPECTATIONS = {
    "runtime-incident-investigator.agent.md": (
        "Runtime Incident Investigator",
        "tools: ['read', 'search', 'execute', 'agent']",
    ),
    "code-evidence.agent.md": (
        "Code Evidence",
        "tools: ['read', 'search']",
    ),
    "log-evidence.agent.md": (
        "Log Evidence",
        "tools: ['read', 'ssh-mcp-server/*']",
    ),
    "database-evidence.agent.md": (
        "Database Evidence",
        "tools: ['read', 'jdbc-explorer/*']",
    ),
    "incident-synthesizer.agent.md": (
        "Incident Synthesizer",
        "tools: ['read']",
    ),
}
EXPECTED_SUBAGENTS = {
    "Code Evidence",
    "Log Evidence",
    "Database Evidence",
    "Incident Synthesizer",
}
EXPECTED_SKILL_DESCRIPTION = (
    "Investigate API failures, runtime exceptions, incorrect states, missing data, "
    "and cross-service issues by correlating local source code, remote service logs "
    "through SSH MCP, and read-only database evidence through JDBC MCP. Use for issues "
    "reported in test, QA, staging, or similar runtime environments when a complete "
    "evidence-backed root-cause analysis, repair plan, and stakeholder response are required."
)
EXPECTED_ARGUMENT_HINT = (
    '"[environment] [approximate time] [API or symptom] [trace ID or business ID]"'
)
MARKDOWN_LINK_PATTERN = re.compile(r"\[[^\]]+\]\(([^)]+)\)")
CJK_PATTERN = re.compile(
    "[\u3400-\u4dbf\u4e00-\u9fff\u3040-\u30ff\uac00-\ud7af]"
)
PRIVATE_KEY_PATTERN = re.compile(r"-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----")
ASSIGNED_SECRET_PATTERN = re.compile(
    r"""(?ix)
    (?:password|passwd|token|secret|private[_-]?key|access[_-]?key)
    \s*[:=]\s*
    ["']?
    (?!TODO_REPLACE_|\[REDACTED|REDACTED|$)
    ([A-Za-z0-9+/_.-]{8,})
    """
)


def repository_root() -> Path:
    return Path(__file__).resolve().parents[4]


def read_text(path: Path, errors: list[str]) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        errors.append(f"{path}: cannot read file: {exc}")
        return ""


def check_required_files(root: Path, errors: list[str]) -> None:
    for relative in REQUIRED_FILES:
        if not (root / relative).is_file():
            errors.append(f"Missing required file: {relative}")


def check_json(root: Path, errors: list[str]) -> None:
    json_files = [
        root / "incident-investigation.code-workspace",
        root / ".vscode/settings.json",
        root / ".github/hooks/incident-investigation.json",
        *(root / SKILL_RELATIVE / "assets").glob("*.json"),
    ]
    for path in json_files:
        if not path.is_file():
            continue
        try:
            json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            errors.append(f"{path.relative_to(root)}: invalid JSON: {exc}")


def frontmatter(text: str) -> str | None:
    match = re.match(r"\A---\r?\n(.*?)\r?\n---\r?\n", text, re.DOTALL)
    return match.group(1) if match else None


def check_skill(root: Path, errors: list[str]) -> None:
    skill_path = root / SKILL_RELATIVE / "SKILL.md"
    if not skill_path.is_file():
        return
    text = read_text(skill_path, errors)
    header = frontmatter(text)
    if header is None:
        errors.append(f"{skill_path.relative_to(root)}: missing YAML frontmatter")
    else:
        name_match = re.search(r"(?m)^name:\s*(\S+)\s*$", header)
        if not name_match:
            errors.append(f"{skill_path.relative_to(root)}: missing skill name")
        elif name_match.group(1) != skill_path.parent.name:
            errors.append(
                f"{skill_path.relative_to(root)}: skill name does not match directory"
            )
        if f"description: {EXPECTED_SKILL_DESCRIPTION}" not in header:
            errors.append(
                f"{skill_path.relative_to(root)}: required description does not match"
            )
        if f"argument-hint: {EXPECTED_ARGUMENT_HINT}" not in header:
            errors.append(
                f"{skill_path.relative_to(root)}: required argument hint does not match"
            )

    if len(text.splitlines()) > 140:
        errors.append(
            f"{skill_path.relative_to(root)}: expected a concise router of about 120 lines"
        )

    linked_files: set[Path] = set()
    for target in MARKDOWN_LINK_PATTERN.findall(text):
        target_path = target.split("#", 1)[0].strip()
        if not target_path or "://" in target_path or target_path.startswith("#"):
            continue
        resolved = (skill_path.parent / target_path).resolve()
        if not resolved.exists():
            errors.append(
                f"{skill_path.relative_to(root)}: broken relative link {target!r}"
            )
        elif resolved.is_file():
            linked_files.add(resolved)

    supporting_files = {
        path.resolve()
        for folder in ("resources", "references", "assets", "scripts")
        for path in (skill_path.parent / folder).glob("*")
        if path.is_file()
    }
    for missing in sorted(supporting_files - linked_files):
        errors.append(
            f"{skill_path.relative_to(root)}: supporting file is not linked: "
            f"{missing.relative_to(skill_path.parent)}"
        )


def check_agents(root: Path, errors: list[str]) -> None:
    agents_dir = root / ".github/agents"
    for filename, (name, tools) in AGENT_EXPECTATIONS.items():
        path = agents_dir / filename
        if not path.is_file():
            continue
        text = read_text(path, errors)
        if f"name: {name}" not in text:
            errors.append(f"{path.relative_to(root)}: required name {name!r} is missing")
        if tools not in text:
            errors.append(
                f"{path.relative_to(root)}: required tool declaration is missing"
            )
        header = frontmatter(text) or ""
        if re.search(r"(?m)^tools:.*\bedit\b", header, re.IGNORECASE):
            errors.append(f"{path.relative_to(root)}: editing tools are prohibited")

    orchestrator = read_text(
        agents_dir / "runtime-incident-investigator.agent.md", errors
    )
    header = frontmatter(orchestrator) or ""
    agent_block = re.search(
        r"(?ms)^agents:\s*\n(.*?)(?=^[A-Za-z][A-Za-z-]*:|\Z)", header
    )
    found_agents = (
        set(re.findall(r"(?m)^\s*-\s+(.+?)\s*$", agent_block.group(1)))
        if agent_block
        else set()
    )
    if found_agents != EXPECTED_SUBAGENTS:
        errors.append(
            ".github/agents/runtime-incident-investigator.agent.md: "
            "the orchestrator must expose exactly the four specified subagents"
        )
    tools_line = next(
        (line for line in header.splitlines() if line.startswith("tools:")), ""
    )
    if "ssh-mcp-server" in tools_line or "jdbc-explorer" in tools_line:
        errors.append(
            ".github/agents/runtime-incident-investigator.agent.md: "
            "the orchestrator must not have direct SSH or JDBC tools"
        )

    for filename, server_id in (
        ("log-evidence.agent.md", "ssh-mcp-server/*"),
        ("database-evidence.agent.md", "jdbc-explorer/*"),
    ):
        path = agents_dir / filename
        text = read_text(path, errors)
        header = frontmatter(text) or ""
        if server_id not in header:
            errors.append(f"{path.relative_to(root)}: missing MCP server ID {server_id}")
        if "PreToolUse:" not in header or "guard_read_only.py" not in header:
            errors.append(f"{path.relative_to(root)}: missing read-only PreToolUse hook")
        for operating_system in ("windows:", "linux:", "osx:"):
            if operating_system not in header:
                errors.append(
                    f"{path.relative_to(root)}: missing {operating_system[:-1]} "
                    "hook command override"
                )


def check_hooks_and_settings(root: Path, errors: list[str]) -> None:
    hook_path = root / ".github/hooks/incident-investigation.json"
    settings_path = root / ".vscode/settings.json"
    try:
        hook = json.loads(hook_path.read_text(encoding="utf-8"))
        entries = hook.get("hooks", {}).get("SubagentStart", [])
        if not entries or not any(
            "inject_evidence_contract.py" in str(entry.get("command", ""))
            for entry in entries
            if isinstance(entry, dict)
        ):
            errors.append(
                ".github/hooks/incident-investigation.json: "
                "missing SubagentStart evidence contract hook"
            )
        for entry in entries:
            if isinstance(entry, dict):
                for operating_system in ("windows", "linux", "osx"):
                    if operating_system not in entry:
                        errors.append(
                            ".github/hooks/incident-investigation.json: "
                            f"missing {operating_system} command override"
                        )
    except (OSError, json.JSONDecodeError):
        pass
    try:
        settings = json.loads(settings_path.read_text(encoding="utf-8"))
        if settings.get("chat.useCustomAgentHooks") is not True:
            errors.append(
                ".vscode/settings.json: chat.useCustomAgentHooks must be true"
            )
    except (OSError, json.JSONDecodeError):
        pass


def check_gitignore(root: Path, errors: list[str]) -> None:
    text = read_text(root / ".gitignore", errors)
    required_pattern = (
        ".github/skills/runtime-incident-investigation/assets/"
        "service-catalog.local.json"
    )
    if required_pattern not in {line.strip() for line in text.splitlines()}:
        errors.append(".gitignore: local service catalog is not ignored")
    if "!.vscode/settings.json" not in text:
        errors.append(".gitignore: .vscode/settings.json is not explicitly included")


def check_python(root: Path, errors: list[str]) -> None:
    scripts_dir = root / SKILL_RELATIVE / "scripts"
    for path in scripts_dir.glob("*.py"):
        try:
            source = path.read_text(encoding="utf-8")
            compile(source, str(path), "exec")
        except (OSError, SyntaxError) as exc:
            errors.append(f"{path.relative_to(root)}: Python compilation failed: {exc}")


def check_content(root: Path, errors: list[str]) -> None:
    for relative in REQUIRED_FILES:
        path = root / relative
        if not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        if path.suffix == ".md" and CJK_PATTERN.search(text):
            errors.append(f"{relative}: Markdown contains non-English CJK text")
        if PRIVATE_KEY_PATTERN.search(text):
            errors.append(f"{relative}: possible private key material detected")
        if ASSIGNED_SECRET_PATTERN.search(text):
            errors.append(f"{relative}: possible assigned credential detected")


def main() -> int:
    root = repository_root()
    errors: list[str] = []
    check_required_files(root, errors)
    check_json(root, errors)
    check_skill(root, errors)
    check_agents(root, errors)
    check_hooks_and_settings(root, errors)
    check_gitignore(root, errors)
    check_python(root, errors)
    check_content(root, errors)

    if errors:
        print(f"Self-check failed with {len(errors)} error(s):", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1

    print(f"OK: runtime incident investigation kit passed {len(REQUIRED_FILES)} file checks.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
