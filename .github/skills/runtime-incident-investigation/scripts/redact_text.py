#!/usr/bin/env python3
"""Redact common secrets and personal data from text evidence."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Iterable


PRIVATE_KEY_PATTERN = re.compile(
    r"-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?"
    r"-----END [A-Z0-9 ]*PRIVATE KEY-----",
    re.DOTALL,
)
AUTHORIZATION_PATTERN = re.compile(
    r"(?im)^(\s*(?:authorization|proxy-authorization)\s*:\s*)\S+(?:\s+\S+)?"
)
SECRET_ASSIGNMENT_PATTERN = re.compile(
    r"""(?ix)
    (
      ["']?
      (?:password|passwd|pwd|token|secret|api[_-]?key|access[_-]?key|
         private[_-]?key|client[_-]?secret|session[_-]?id|cookie)
      ["']?
      \s*[:=]\s*
    )
    (["']?)
    ([^"',\s}\]]+)
    \2
    """
)
JWT_PATTERN = re.compile(
    r"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{8,}\b"
)
URL_CREDENTIAL_PATTERN = re.compile(
    r"(?i)\b([a-z][a-z0-9+.-]*://)([^/\s:@]+):([^@\s/]+)@"
)
EMAIL_PATTERN = re.compile(
    r"(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}(?![A-Za-z0-9.-])"
)
PHONE_PATTERN = re.compile(
    r"(?<!\w)(?:\+?\d[\d ()-]{6,}\d)(?!\w)"
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Redact likely secrets, contact data, and configured sensitive fields."
    )
    parser.add_argument(
        "input",
        nargs="?",
        type=Path,
        help="Input file. Omit or use '-' to read stdin.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        help="Explicit output file. Without this option, write to stdout.",
    )
    parser.add_argument(
        "--sensitive-column",
        action="append",
        default=[],
        help="Column or field name whose key-value representation must be redacted.",
    )
    parser.add_argument(
        "--sensitive-values-file",
        type=Path,
        help="JSON object mapping sensitive column names to values or value arrays.",
    )
    return parser.parse_args()


def redact_named_field(text: str, field: str) -> str:
    escaped = re.escape(field)
    quoted_pattern = re.compile(
        rf"""(?ix)
        (
          ["']?{escaped}["']?
          \s*[:=]\s*
        )
        (["'])
        .*?
        \2
        """
    )
    text = quoted_pattern.sub(r"\1\"[REDACTED]\"", text)
    unquoted_pattern = re.compile(
        rf"""(?ix)
        (
          ["']?{escaped}["']?
          \s*[:=]\s*
        )
        [^,\s}}\]]+
        """
    )
    return unquoted_pattern.sub(r"\1[REDACTED]", text)


def load_sensitive_values(path: Path | None) -> tuple[list[str], list[str]]:
    if path is None:
        return [], []
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError("sensitive values file must contain a JSON object")

    columns: list[str] = []
    values: list[str] = []
    for column, raw_values in data.items():
        columns.append(str(column))
        items: Iterable[object]
        if isinstance(raw_values, list):
            items = raw_values
        else:
            items = [raw_values]
        for value in items:
            text = str(value)
            if text:
                values.append(text)
    return columns, values


def redact(text: str, columns: list[str], literal_values: list[str]) -> str:
    text = PRIVATE_KEY_PATTERN.sub("[REDACTED_PRIVATE_KEY]", text)
    text = AUTHORIZATION_PATTERN.sub(r"\1[REDACTED_AUTHORIZATION]", text)
    text = SECRET_ASSIGNMENT_PATTERN.sub(r"\1[REDACTED_SECRET]", text)
    text = JWT_PATTERN.sub("[REDACTED_TOKEN]", text)
    text = URL_CREDENTIAL_PATTERN.sub(r"\1[REDACTED_USER]:[REDACTED_PASSWORD]@", text)

    for column in columns:
        if column.strip():
            text = redact_named_field(text, column.strip())
    for value in sorted(set(literal_values), key=len, reverse=True):
        text = text.replace(value, "[REDACTED_SENSITIVE_VALUE]")

    text = EMAIL_PATTERN.sub("[REDACTED_EMAIL]", text)
    text = PHONE_PATTERN.sub("[REDACTED_PHONE]", text)
    return text


def read_input(path: Path | None) -> str:
    if path is None or str(path) == "-":
        return sys.stdin.read()
    return path.read_text(encoding="utf-8")


def main() -> int:
    args = parse_args()
    try:
        extra_columns, literal_values = load_sensitive_values(
            args.sensitive_values_file
        )
        source = read_input(args.input)
        result = redact(
            source,
            [*args.sensitive_column, *extra_columns],
            literal_values,
        )
        if args.output is None:
            sys.stdout.write(result)
        else:
            args.output.write_text(result, encoding="utf-8")
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"ERROR: redaction failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
