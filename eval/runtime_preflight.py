#!/usr/bin/env python3
"""Helpers for failing fast when a local benchmark runtime is unavailable."""

from __future__ import annotations

import json
import socket
import subprocess
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def is_local_base_url(base_url: str) -> bool:
    hostname = urllib.parse.urlparse(base_url).hostname
    return hostname in {"localhost", "127.0.0.1", "::1"}


def check_docker_daemon(timeout: int = 5) -> dict[str, Any] | None:
    try:
        completed = subprocess.run(
            ["docker", "info"],
            check=True,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except FileNotFoundError:
        return {
            "kind": "docker-cli-missing",
            "message": "docker CLI가 없어 로컬 benchmark runtime을 확인할 수 없습니다.",
        }
    except subprocess.TimeoutExpired:
        return {
            "kind": "docker-daemon-timeout",
            "message": "docker info가 시간 안에 끝나지 않았습니다.",
        }
    except subprocess.CalledProcessError as exc:
        detail = (exc.stderr or exc.stdout or "").strip().splitlines()
        return {
            "kind": "docker-daemon-unavailable",
            "message": "Docker daemon에 연결할 수 없습니다.",
            "detail": detail[0] if detail else "docker info failed",
        }

    if completed.returncode != 0:
        return {
            "kind": "docker-daemon-unavailable",
            "message": "Docker daemon에 연결할 수 없습니다.",
        }
    return None


def probe_agent_status(base_url: str, timeout: int) -> dict[str, Any] | None:
    status_url = f"{base_url.rstrip('/')}/api/v2/status"
    request = urllib.request.Request(status_url, headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = json.load(response)
    except urllib.error.HTTPError as exc:
        return {
            "kind": "agent-status-http-error",
            "message": f"에이전트 상태 엔드포인트가 HTTP {exc.code}를 반환했습니다.",
            "detail": status_url,
        }
    except (TimeoutError, urllib.error.URLError, json.JSONDecodeError, socket.timeout) as exc:
        return {
            "kind": "agent-unreachable",
            "message": "에이전트 상태 엔드포인트에 연결할 수 없습니다.",
            "detail": f"{type(exc).__name__}: {exc}",
        }

    return {
        "kind": "ok",
        "message": "runtime ready",
        "detail": payload,
    }


def detect_runtime_blocker(base_url: str, timeout: int, require_docker: bool) -> dict[str, Any] | None:
    if require_docker and is_local_base_url(base_url):
        docker_blocker = check_docker_daemon()
        if docker_blocker is not None:
            return docker_blocker

    status = probe_agent_status(base_url, timeout)
    if status is None or status["kind"] == "ok":
        return None
    return status


def write_blocked_report(output: str, metadata: dict[str, Any], blocker: dict[str, Any]) -> None:
    report = {
        "metadata": metadata,
        "status": "blocked",
        "blockedAt": datetime.now(timezone.utc).isoformat(),
        "blocker": blocker,
        "summary": {
            "status": "blocked",
            "reason": blocker["kind"],
        },
        "rows": [],
    }
    output_path = Path(output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
