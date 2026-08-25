#!/usr/bin/env python3
"""평가 질문에 현재 RuleBasedRouter의 결정 키워드가 섞였는지 검사한다."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

EXPECTED_BLOCKS = {"sqlKeywords", "graphKeywords", "vectorKeywords"}


def extract_keywords(source: str) -> list[str]:
    blocks = {
        name: body
        for name, body in re.findall(
            r"private val (\w+Keywords) = listOf\((.*?)\n    \)",
            source,
            re.DOTALL,
        )
    }
    missing = EXPECTED_BLOCKS - blocks.keys()
    if missing:
        raise ValueError(f"router keyword blocks not found: {', '.join(sorted(missing))}")

    keywords = [
        value.lower()
        for name in sorted(EXPECTED_BLOCKS)
        for value in re.findall(r'"([^"]+)"', blocks[name])
    ]
    if not keywords:
        raise ValueError("router keyword extraction returned no keywords")
    return keywords


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset")
    parser.add_argument("--router", default="agent-app-spring/src/main/kotlin/com/riwonace/agent/router/RuleBasedRouter.kt")
    args = parser.parse_args()

    source = Path(args.router).read_text(encoding="utf-8")
    try:
        keywords = extract_keywords(source)
    except ValueError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    questions = json.loads(Path(args.dataset).read_text(encoding="utf-8"))["questions"]
    violations = []
    for item in questions:
        lowered = item["question"].lower()
        matched = [keyword for keyword in keywords if keyword in lowered]
        if matched:
            violations.append((item["id"], matched))

    if violations:
        for item_id, matched in violations:
            print(f"{item_id}: {', '.join(matched)}")
        return 1
    print(f"OK: {len(questions)} questions, {len(keywords)} router keywords, no overlap")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
