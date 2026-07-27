#!/usr/bin/env python3
"""Search bounded source roots in exactly one catalog-configured repository."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any, Iterable


SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_CATALOG = SCRIPT_DIR.parent / "assets" / "service-catalog.local.json"
DEFAULT_ROOTS = ("src/main/java", "src/main/resources")
EXCLUDED_PARTS = {
    ".git", ".idea", ".vscode", "target", "build", "out", "node_modules",
    "generated", "generated-sources", "generated_sources",
}


class SearchError(ValueError):
    """A safe, user-actionable search rejection."""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("service", help="Catalog service key or exact configured repository path.")
    parser.add_argument("query", help="Literal text or regular expression to find.")
    parser.add_argument("--subdir", action="append", default=[], help="Relative subdirectory within an allowed source root; repeat as needed.")
    parser.add_argument("--regex", action="store_true", help="Interpret query as a Python regular expression.")
    parser.add_argument("--include-tests", action="store_true", help="Also allow src/test/java.")
    parser.add_argument("--max-lines", type=int, default=40)
    parser.add_argument("--max-files", type=int, default=8)
    return parser.parse_args()


def canonical(path: Path) -> Path:
    return path.expanduser().resolve(strict=False)


def is_within(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def load_service(catalog_path: Path, key: str) -> tuple[str, Path]:
    try:
        data = json.loads(catalog_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise SearchError(f"cannot read service catalog: {exc}") from exc
    services = data.get("services") if isinstance(data, dict) else None
    if not isinstance(services, list):
        raise SearchError("service catalog has no services array")

    requested_path = canonical(Path(key))
    for item in services:
        if not isinstance(item, dict) or not isinstance(item.get("localRepositoryPath"), str):
            continue
        name = str(item.get("name", ""))
        aliases = [str(alias) for alias in item.get("aliases", [])]
        repository = canonical(Path(item["localRepositoryPath"]))
        if key.casefold() in {name.casefold(), *(alias.casefold() for alias in aliases)} or requested_path == repository:
            if not repository.is_dir():
                raise SearchError(f"configured repository does not exist: {repository}")
            return name, repository
    raise SearchError("unknown service key or unconfigured repository path")


def allowed_roots(repository: Path, include_tests: bool, subdirs: list[str]) -> list[Path]:
    bases = [*DEFAULT_ROOTS, *(("src/test/java",) if include_tests else ())]
    roots: list[Path] = []
    if not subdirs:
        candidates = [repository / base for base in bases]
    else:
        candidates = []
        for raw in subdirs:
            relative = Path(raw)
            if relative.is_absolute():
                raise SearchError("subdirectories must be relative")
            candidate = canonical(repository / relative)
            if not is_within(candidate, repository):
                raise SearchError("subdirectory traversal outside the configured repository is prohibited")
            if not any(is_within(candidate, canonical(repository / base)) for base in bases):
                raise SearchError("subdirectory is outside the allowed source roots")
            candidates.append(candidate)
    for candidate in candidates:
        resolved = canonical(candidate)
        if not is_within(resolved, repository):
            raise SearchError("source root traversal outside the configured repository is prohibited")
        if resolved.is_dir():
            roots.append(resolved)
    return sorted(set(roots), key=lambda path: path.as_posix())


def source_files(roots: Iterable[Path], repository: Path) -> Iterable[Path]:
    seen: set[Path] = set()
    for root in roots:
        for path in sorted(root.rglob("*"), key=lambda item: item.as_posix()):
            if not path.is_file() or path in seen:
                continue
            resolved = canonical(path)
            if not is_within(resolved, repository) or not is_within(resolved, root):
                continue
            relative = path.relative_to(repository)
            if any(part.casefold() in EXCLUDED_PARTS for part in relative.parts):
                continue
            seen.add(path)
            yield path


def search(catalog_path: Path, service_key: str, query: str, *, regex: bool = False,
           include_tests: bool = False, subdirs: list[str] | None = None,
           max_lines: int = 40, max_files: int = 8) -> dict[str, Any]:
    if max_lines < 1 or max_files < 1:
        raise SearchError("result limits must be positive")
    if not query:
        raise SearchError("query must not be empty")
    service, repository = load_service(catalog_path, service_key)
    roots = allowed_roots(repository, include_tests, subdirs or [])
    try:
        pattern = re.compile(query if regex else re.escape(query))
    except re.error as exc:
        raise SearchError(f"invalid regular expression: {exc}") from exc

    matches: list[dict[str, Any]] = []
    matched_files: list[str] = []
    truncated = False
    for path in source_files(roots, repository):
        file_matches: list[dict[str, Any]] = []
        try:
            with path.open("r", encoding="utf-8", errors="replace") as handle:
                for number, line in enumerate(handle, 1):
                    if pattern.search(line):
                        file_matches.append({
                            "file": path.relative_to(repository).as_posix(),
                            "line": number,
                            "text": line.rstrip("\r\n")[:500],
                        })
                        if len(matches) + len(file_matches) >= max_lines:
                            truncated = True
                            break
        except OSError:
            continue
        if file_matches:
            matched_files.append(path.relative_to(repository).as_posix())
            matches.extend(file_matches)
            if truncated or len(matched_files) >= max_files:
                truncated = True
                break

    return {
        "service": service,
        "repository": repository.as_posix(),
        "query": query,
        "files": matched_files,
        "matching_lines": matches,
        "truncated": truncated,
    }


def main() -> int:
    args = parse_args()
    try:
        result = search(DEFAULT_CATALOG, args.service, args.query, regex=args.regex,
                        include_tests=args.include_tests, subdirs=args.subdir,
                        max_lines=args.max_lines, max_files=args.max_files)
    except SearchError as exc:
        print(json.dumps({"error": str(exc)}, sort_keys=True), file=sys.stderr)
        return 2
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
