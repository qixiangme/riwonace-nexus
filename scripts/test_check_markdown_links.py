from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from check_markdown_links import broken_links_in_file


class CheckMarkdownLinksTest(unittest.TestCase):
    def test_existing_relative_link_passes(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            target = root / "docs" / "guide.md"
            target.parent.mkdir(parents=True)
            target.write_text("# guide\n", encoding="utf-8")
            source = root / "README.md"
            source.write_text("[guide](./docs/guide.md)\n", encoding="utf-8")
            self.assertEqual(broken_links_in_file(source), [])

    def test_missing_relative_link_is_reported(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir) / "README.md"
            source.write_text("[missing](./docs/missing.md)\n", encoding="utf-8")
            self.assertEqual(broken_links_in_file(source), ["./docs/missing.md"])


if __name__ == "__main__":
    unittest.main()
