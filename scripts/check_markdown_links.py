#!/usr/bin/env python3
"""Fail when tracked Markdown files contain broken local relative links."""

from __future__ import annotations

import re
import sys
from pathlib import Path


MARKDOWN_FILES = (
    "README.md",
    "CONTRIBUTING.md",
    "BENCHMARK.md",
    "ARCHITECTURE.md",
    "SECURITY.md",
    "docs/**/*.md",
    "agent-app-go/**/*.md",
    "mcp-server-go/**/*.md",
    "mcp-server-air/**/*.md",
)
LINK_PATTERN = re.compile(r"\[[^\]]+\]\((?!https?://|mailto:|#)([^)]+)\)")


def markdown_files(root: Path) -> list[Path]:
    files: list[Path] = []
    for pattern in MARKDOWN_FILES:
        files.extend(root.glob(pattern))
    return sorted({path for path in files if path.is_file()})


def broken_links_in_file(path: Path) -> list[str]:
    broken: list[str] = []
    text = path.read_text(encoding="utf-8")
    for match in LINK_PATTERN.finditer(text):
        target = match.group(1).strip()
        target_path = target.split("#", 1)[0]
        if not target_path:
            continue
        resolved = (path.parent / target_path).resolve()
        if not resolved.exists():
            broken.append(target)
    return broken


def main(argv: list[str]) -> int:
    root = Path(argv[1]).resolve() if len(argv) > 1 else Path.cwd()
    broken_entries: list[str] = []
    for path in markdown_files(root):
        for target in broken_links_in_file(path):
            broken_entries.append(f"{path.relative_to(root)} -> {target}")

    if broken_entries:
        print("Broken Markdown links found:", file=sys.stderr)
        for entry in broken_entries:
            print(f"- {entry}", file=sys.stderr)
        return 1

    print(f"Checked {len(markdown_files(root))} Markdown files: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
