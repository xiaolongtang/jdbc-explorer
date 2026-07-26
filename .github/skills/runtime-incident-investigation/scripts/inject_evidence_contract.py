#!/usr/bin/env python3
"""Inject the common evidence contract into incident subagents."""

from __future__ import annotations

import json
import sys
from typing import Any


INCIDENT_AGENTS = {
    "Code Evidence",
    "Log Evidence",
    "Database Evidence",
    "Incident Synthesizer",
}

EVIDENCE_CONTRACT = """Evidence contract:
- Do not invent evidence.
- Separate observations from interpretations and assumptions.
- Include exact source locations.
- State what each evidence item does and does not prove.
- Report gaps, blocked operations, contradictions, and uncertainty.
- Do not silently broaden the assigned scope.
- Evidence collection agents must not produce a final root cause.
- Follow the read-only policy: do not modify code, server files, services, infrastructure, or database data.
- Preserve stable evidence IDs and redact secrets, personal data, and unrelated business values.
"""


def output_for(agent_type: str) -> dict[str, Any]:
    if agent_type not in INCIDENT_AGENTS:
        return {}
    return {
        "hookSpecificOutput": {
            "hookEventName": "SubagentStart",
            "additionalContext": EVIDENCE_CONTRACT,
        }
    }


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, OSError):
        print("{}")
        return 0

    agent_type = payload.get("agent_type", "") if isinstance(payload, dict) else ""
    print(json.dumps(output_for(str(agent_type))))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
