#!/usr/bin/env python3
"""실행 중인 agent-app의 라우팅과 최종 답변 정확도를 함께 평가한다."""

from __future__ import annotations

import argparse
import json
import subprocess
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from answer_rules import grade_answer
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


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--set", default="eval/keyword-gap-eval.json", dest="set_file")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--reps", type=int, default=1)
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--output", default="eval/results/full-eval.json")
    parser.add_argument("--model-label", default="unknown")
    parser.add_argument("--router-label", default="unknown")
    parser.add_argument("--mcp-label", default="unknown")
    parser.add_argument("--skip-runtime-preflight", action="store_true")
    args = parser.parse_args()

    metadata = {
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "commit": git_value("rev-parse", "HEAD"),
        "branch": git_value("branch", "--show-current"),
        "dirty": bool(git_value("status", "--porcelain", "--untracked-files=no")),
        "dataset": args.set_file,
        "baseUrl": args.base_url,
        "reps": args.reps,
        "model": args.model_label,
        "router": args.router_label,
        "mcpServer": args.mcp_label,
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

    questions = json.loads(Path(args.set_file).read_text(encoding="utf-8"))["questions"]
    rows: list[dict[str, object]] = []
    for rep in range(1, args.reps + 1):
        for item in questions:
            error = None
            response: dict[str, object] = {}
            elapsed = 0
            try:
                response, elapsed = ask(args.base_url, item["question"], args.timeout)
            except (TimeoutError, urllib.error.URLError, json.JSONDecodeError) as exc:
                error = f"{type(exc).__name__}: {exc}"

            answer = str(response.get("answer", ""))
            routes = [str(route) for route in response.get("routes", [])]
            grading = grade_answer(answer, item)
            row = {
                "rep": rep,
                "id": item["id"],
                "question": item["question"],
                "expectedRoute": item["expectedRoute"],
                "actualRoutes": routes,
                "routeCorrect": item["expectedRoute"] in routes,
                **grading,
                "latencyMs": response.get("latencyMs", elapsed),
                "wallMs": elapsed,
                "toolCalls": response.get("toolCalls", []),
                "contextSources": response.get("contextSources", []),
                "answer": answer,
                "error": error,
            }
            rows.append(row)
            print(
                f"[{item['id']} rep{rep}] route={'O' if row['routeCorrect'] else 'X'} "
                f"answer={'O' if row['answerCorrect'] else 'X'} actual={'+'.join(routes) or 'ERROR'} "
                f"{row['latencyMs']}ms",
                flush=True,
            )

    report = build_report(
        metadata,
        rows,
    )
    summary = report["summary"]
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"saved: {output}")
    return 0 if summary["errors"] == 0 else 2


if __name__ == "__main__":
    raise SystemExit(main())
