from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "scripts" / "scoped_code_search.py"
SPEC = importlib.util.spec_from_file_location("scoped_code_search", SCRIPT)
assert SPEC and SPEC.loader
scoped = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(scoped)


class ScopedCodeSearchTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.repo = self.root / "configured"
        self.sibling = self.root / "unrelated"
        for relative, text in {
            "src/main/java/App.java": "needle main\nneedle second\n",
            "src/main/resources/app.properties": "key=needle\n",
            "src/test/java/AppTest.java": "needle test\n",
            "target/generated/Generated.java": "needle target\n",
            "build/classes/Built.java": "needle build\n",
            "README.md": "needle readme\n",
        }.items():
            path = self.repo / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        (self.sibling / "src/main/java").mkdir(parents=True)
        (self.sibling / "src/main/java/Other.java").write_text("needle sibling\n", encoding="utf-8")
        self.catalog = self.root / "catalog.json"
        self.catalog.write_text(json.dumps({"services": [{
            "name": "configured-service",
            "aliases": ["configured"],
            "localRepositoryPath": str(self.repo),
        }]}), encoding="utf-8")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def run_search(self, **kwargs):
        return scoped.search(self.catalog, "configured-service", "needle", **kwargs)

    def test_search_stays_in_repository_and_never_scans_sibling(self) -> None:
        result = self.run_search()
        self.assertEqual(result["repository"], self.repo.as_posix())
        self.assertNotIn("Other.java", json.dumps(result))

    def test_unknown_service_is_rejected(self) -> None:
        with self.assertRaises(scoped.SearchError):
            scoped.search(self.catalog, "missing", "needle")

    def test_unconfigured_path_and_traversal_are_rejected(self) -> None:
        with self.assertRaises(scoped.SearchError):
            scoped.search(self.catalog, str(self.sibling), "needle")
        with self.assertRaises(scoped.SearchError):
            self.run_search(subdirs=["src/main/java/../../../../unrelated"])

    def test_excluded_build_directories_are_ignored(self) -> None:
        result = self.run_search()
        rendered = json.dumps(result)
        self.assertNotIn("target", rendered)
        self.assertNotIn("build", rendered)

    def test_result_limits_are_enforced(self) -> None:
        result = self.run_search(max_lines=1, max_files=1)
        self.assertEqual(len(result["matching_lines"]), 1)
        self.assertEqual(len(result["files"]), 1)
        self.assertTrue(result["truncated"])

    def test_only_default_roots_are_searched(self) -> None:
        result = self.run_search()
        self.assertNotIn("README.md", result["files"])
        self.assertIn("src/main/java/App.java", result["files"])
        self.assertIn("src/main/resources/app.properties", result["files"])

    def test_tests_require_explicit_flag(self) -> None:
        self.assertNotIn("src/test/java/AppTest.java", self.run_search()["files"])
        self.assertIn("src/test/java/AppTest.java", self.run_search(include_tests=True)["files"])


if __name__ == "__main__":
    unittest.main()
