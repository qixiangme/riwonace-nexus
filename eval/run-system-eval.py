#!/usr/bin/env python3
"""Run reproducible end-to-end evaluation against a fixed agent SHA."""

from __future__ import annotations

import argparse
import hashlib
import json
import random
import subprocess
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from answer_rules import grade_answer
from claim_support import evaluate_claim_support
from result_report import build_report
from runtime_preflight import detect_runtime_blocker, is_local_base_url, write_blocked_report


def git_value(*args: str) -> str:
    try:
        return subprocess.check_output(["git", *args], text=True, timeout=5).strip()
    except (OSError, subprocess.SubprocessError):
        return "unknown"


def ask(base_url: str, question: str, timeout: int) -> tuple[dict[str, object], int]:
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/api/chat",
        data=json.dumps({"question": question}, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json; charset=utf-8"},
    )
    started = time.perf_counter()
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.load(response), round((time.perf_counter() - started) * 1000)


def expected_routes(item: dict[str, object]) -> list[str]:
    raw = item.get("expectedRoutes")
    if isinstance(raw, list):
        return [str(route).upper() for route in raw]
    return [str(item["expectedRoute"]).upper()]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--set", required=True, dest="set_file")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--reps", type=int, default=3)
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--seed", type=int, default=20260813)
    parser.add_argument("--split", choices=("all", "dev", "holdout"), default="all")
    parser.add_argument("--limit", type=int, default=None, help="exploratory smoke only; final reports omit this")
    parser.add_argument("--output", required=True)
    parser.add_argument("--model-label", required=True)
    parser.add_argument("--system-mode", choices=("NO_TOOLS", "VANILLA_MCP", "OURS"), required=True)
    parser.add_argument("--context-policy", default="CURRENT")
    parser.add_argument("--mcp-label", default="spring-ai")
    parser.add_argument("--allow-dirty", action="store_true")
    parser.add_argument("--skip-runtime-preflight", action="store_true")
    args = parser.parse_args()

    dirty = bool(git_value("status", "--porcelain", "--untracked-files=no"))
    if dirty and not args.allow_dirty:
        raise SystemExit("refusing benchmark from dirty tracked worktree; commit first or use --allow-dirty for exploration")

    metadata = {
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "commit": git_value("rev-parse", "HEAD"),
        "branch": git_value("branch", "--show-current"),
        "dirty": dirty,
        "dataset": args.set_file,
        "datasetSha256": hashlib.sha256(Path(args.set_file).read_bytes()).hexdigest(),
        "baseUrl": args.base_url,
        "reps": args.reps,
        "seed": args.seed,
        "split": args.split,
        "limit": args.limit,
        "model": args.model_label,
        "systemMode": args.system_mode,
        "contextPolicy": args.context_policy,
        "mcpServer": args.mcp_label,
        "evaluatorVersion": "3.0",
        "runtimePreflight": {
            "enabled": not args.skip_runtime_preflight,
            "requiresDocker": is_local_base_url(args.base_url),
        },
    }
    if not args.skip_runtime_preflight:
        blocker = detect_runtime_blocker(
            args.base_url,
            timeout=min(args.timeout, 5),
            require_docker=is_local_base_url(args.base_url),
        )
        if blocker is not None:
            write_blocked_report(args.output, metadata, blocker)
            print(f"blocked: {blocker['kind']} - {blocker['message']}")
            print(f"saved: {args.output}")
            return 3

    data = json.loads(Path(args.set_file).read_text(encoding="utf-8"))
    questions = data["questions"]
    if args.split != "all":
        questions = [item for item in questions if item.get("split") == args.split]
        if not questions:
            raise SystemExit(f"dataset has no questions for split={args.split}")
    if args.limit is not None:
        if args.limit < 1:
            raise SystemExit("--limit must be at least 1")
        questions = questions[:args.limit]
    if args.reps < 1:
        raise SystemExit("--reps must be at least 1")

    rows: list[dict[str, object]] = []
    rng = random.Random(args.seed)
    for rep in range(1, args.reps + 1):
        ordered = list(questions)
        rng.shuffle(ordered)
        for item in ordered:
            error = None
            response: dict[str, object] = {}
            elapsed = 0
            try:
                response, elapsed = ask(args.base_url, str(item["question"]), args.timeout)
            except (TimeoutError, urllib.error.URLError, json.JSONDecodeError) as exc:
                error = f"{type(exc).__name__}: {exc}"

            answer = str(response.get("answer", ""))
            actual = [str(route).upper() for route in response.get("routes", [])]
            expected = expected_routes(item)
            grading = grade_answer(answer, item)
            claim_support = evaluate_claim_support(
                answer,
                str(item["question"]),
                response.get("selectedEvidence"),
            )
            row = {
                "rep": rep,
                "id": item["id"],
                "split": item.get("split", "unspecified"),
                "type": item.get("type", "unknown"),
                "question": item["question"],
                "expectedRoute": "+".join(expected),
                "expectedRoutes": expected,
                "actualRoutes": actual,
                "routeCorrect": set(actual) == set(expected),
                "routeRecallCorrect": set(expected).issubset(actual),
                **grading,
                "latencyMs": response.get("latencyMs", elapsed),
                "wallMs": elapsed,
                "toolCalls": response.get("toolCalls", []),
                "contextSources": response.get("contextSources", []),
                "failedRoutes": response.get("failedRoutes", []),
                "responseMode": response.get("responseMode", "UNKNOWN"),
                "contextChars": response.get("contextChars", 0),
                "estimatedContextTokens": response.get("estimatedContextTokens", 0),
                "claimSupport": claim_support,
                "selectedEvidence": response.get("selectedEvidence"),
                "answer": answer,
                "error": error,
            }
            rows.append(row)
            print(
                f"[{item['id']} rep{rep}] route={'O' if row['routeCorrect'] else 'X'} "
                f"answer={'O' if row['answerCorrect'] else 'X'} mode={row['responseMode']} "
                f"{row['latencyMs']}ms",
                flush=True,
            )

    metadata["datasetVersion"] = data.get("version", "unknown")
    report = build_report(metadata, rows)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report["summary"], ensure_ascii=False, indent=2))
    print(f"saved: {output}")
    return 0 if report["summary"]["errors"] == 0 else 2


if __name__ == "__main__":
    raise SystemExit(main())
