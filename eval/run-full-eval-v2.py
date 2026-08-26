#!/usr/bin/env python3
"""
실행 중인 agent-app의 라우팅과 최종 답변 정확도를 함께 평가한다.
v2: answerRule 기반 명시적 채점 규칙 지원
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import time
import urllib.error
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


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


def score_answer(answer: str, item: dict[str, Any]) -> tuple[bool, list[str], str]:
    """
    answerRule 기반 채점. 규칙 유형:
    - anyOf: required 키워드 중 하나 이상 포함
    - allOf: required 키워드 전부 포함
    - minMatches: from 키워드 중 count개 이상 포함
    - numeric: 숫자가 포함되어 있으면 통과

    Returns: (correct, matched_keywords, rule_used)
    """
    # 천단위 쉼표(예: "7,917만원")는 채점 대상 숫자 키워드(예: "7917만원")와
    # 값이 같지만 부분 문자열 포함 검사로는 걸리지 않는다 — 답변/키워드 양쪽에서
    # 쉼표를 제거해 비교한다.
    answer_lower = answer.casefold().replace(",", "")
    keywords = [str(k).replace(",", "") for k in item.get("keywords", [])]
    rule = item.get("answerRule")

    if rule is None:
        # 기존 방식: 키워드 중 하나만 맞아도 통과 (후방 호환)
        matched = [k for k in keywords if k.casefold() in answer_lower]
        return bool(matched), matched, "legacy-anyOf"

    rule_type = rule.get("type", "anyOf")

    if rule_type == "anyOf":
        required = [str(k).replace(",", "") for k in rule.get("required", keywords)]
        matched = [k for k in required if k.casefold() in answer_lower]
        return bool(matched), matched, "anyOf"

    elif rule_type == "allOf":
        required = [str(k).replace(",", "") for k in rule.get("required", keywords)]
        matched = [k for k in required if k.casefold() in answer_lower]
        return len(matched) == len(required), matched, "allOf"

    elif rule_type == "minMatches":
        from_list = [str(k).replace(",", "") for k in rule.get("from", keywords)]
        count = rule.get("count", 1)
        matched = [k for k in from_list if k.casefold() in answer_lower]
        return len(matched) >= count, matched, f"minMatches({count})"

    elif rule_type == "numeric":
        # 숫자가 포함되어 있으면 통과
        has_number = bool(re.search(r"\d+", answer))
        matched = [k for k in keywords if k.casefold() in answer_lower]
        return has_number, matched, "numeric"

    else:
        # 알 수 없는 규칙은 기존 방식으로 폴백
        matched = [k for k in keywords if k.casefold() in answer_lower]
        return bool(matched), matched, f"unknown({rule_type})"


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
    parser.add_argument("--route-filter", default=None, help="특정 라우트만 평가 (SQL, VECTOR, GRAPH)")
    args = parser.parse_args()

    data = json.loads(Path(args.set_file).read_text(encoding="utf-8"))
    questions = data["questions"]

    # 라우트 필터링
    if args.route_filter:
        questions = [q for q in questions if q["expectedRoute"] == args.route_filter.upper()]
        if not questions:
            print(f"No questions for route filter: {args.route_filter}")
            return 1

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

            # 명시적 채점 규칙 적용
            correct, matched, rule_used = score_answer(answer, item)

            row = {
                "rep": rep,
                "id": item["id"],
                "type": item.get("type", "unknown"),
                "question": item["question"],
                "expectedRoute": item["expectedRoute"],
                "actualRoutes": routes,
                "routeCorrect": item["expectedRoute"] in routes,
                "answerCorrect": correct,
                "matchedKeywords": matched,
                "ruleUsed": rule_used,
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
                f"answer={'O' if row['answerCorrect'] else 'X'}({rule_used}) "
                f"actual={'+'.join(routes) or 'ERROR'} {row['latencyMs']}ms",
                flush=True,
            )

    total = len(rows)
    if total == 0:
        print("No rows to evaluate")
        return 1

    answer_correct = sum(bool(row["answerCorrect"]) for row in rows)
    route_correct = sum(bool(row["routeCorrect"]) for row in rows)
    valid_latencies = [int(row["latencyMs"]) for row in rows if row["error"] is None]

    # 라우트별 통계
    by_route = {}
    for route in ("SQL", "VECTOR", "GRAPH"):
        selected = [row for row in rows if row["expectedRoute"] == route]
        if selected:
            by_route[route] = {
                "answerAccuracyPct": round(100 * sum(bool(row["answerCorrect"]) for row in selected) / len(selected), 1),
                "routeAccuracyPct": round(100 * sum(bool(row["routeCorrect"]) for row in selected) / len(selected), 1),
                "count": len(selected),
            }

    # 타입별 통계
    by_type = {}
    type_counts = Counter(row.get("type", "unknown") for row in rows)
    for qtype in type_counts:
        selected = [row for row in rows if row.get("type") == qtype]
        if selected:
            by_type[qtype] = {
                "answerAccuracyPct": round(100 * sum(bool(row["answerCorrect"]) for row in selected) / len(selected), 1),
                "routeAccuracyPct": round(100 * sum(bool(row["routeCorrect"]) for row in selected) / len(selected), 1),
                "count": len(selected),
            }

    failure_types = Counter(
        "request-error" if row["error"] else
        "route-and-answer" if not row["routeCorrect"] and not row["answerCorrect"] else
        "route-only" if not row["routeCorrect"] else
        "answer-only" if not row["answerCorrect"] else "pass"
        for row in rows
    )

    summary = {
        "answerAccuracyPct": round(100 * answer_correct / total, 1),
        "routeAccuracyPct": round(100 * route_correct / total, 1),
        "answerCorrect": answer_correct,
        "routeCorrect": route_correct,
        "total": total,
        "errors": sum(row["error"] is not None for row in rows),
        "averageLatencyMs": round(sum(valid_latencies) / len(valid_latencies)) if valid_latencies else None,
        "byExpectedRoute": by_route,
        "byQuestionType": by_type,
        "outcomes": dict(sorted(failure_types.items())),
    }

    report = {
        "metadata": {
            "createdAt": datetime.now(timezone.utc).isoformat(),
            "commit": git_value("rev-parse", "HEAD"),
            "branch": git_value("branch", "--show-current"),
            "dirty": bool(git_value("status", "--porcelain", "--untracked-files=no")),
            "dataset": args.set_file,
            "datasetVersion": data.get("version", "unknown"),
            "baseUrl": args.base_url,
            "reps": args.reps,
            "model": args.model_label,
            "router": args.router_label,
            "mcpServer": args.mcp_label,
            "routeFilter": args.route_filter,
            "evaluatorVersion": "2.0",
        },
        "summary": summary,
        "rows": rows,
    }

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"saved: {output}")
    return 0 if summary["errors"] == 0 else 2


if __name__ == "__main__":
    raise SystemExit(main())
