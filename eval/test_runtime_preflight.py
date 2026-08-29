from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from runtime_preflight import (
    check_docker_daemon,
    detect_runtime_blocker,
    is_local_base_url,
    write_blocked_report,
)


class RuntimePreflightTest(unittest.TestCase):
    def test_local_base_url_detection(self) -> None:
        self.assertTrue(is_local_base_url("http://localhost:8080"))
        self.assertTrue(is_local_base_url("http://127.0.0.1:8080"))
        self.assertFalse(is_local_base_url("https://example.com"))

    @patch("runtime_preflight.check_docker_daemon")
    @patch("runtime_preflight.probe_agent_status")
    def test_docker_blocker_short_circuits_agent_probe(self, probe_status, docker_check) -> None:
        docker_check.return_value = {"kind": "docker-daemon-unavailable", "message": "down"}
        blocker = detect_runtime_blocker("http://localhost:8080", timeout=5, require_docker=True)
        self.assertEqual(blocker["kind"], "docker-daemon-unavailable")
        probe_status.assert_not_called()

    @patch("runtime_preflight.probe_agent_status")
    def test_agent_blocker_is_returned_for_remote_targets(self, probe_status) -> None:
        probe_status.return_value = {"kind": "agent-unreachable", "message": "down"}
        blocker = detect_runtime_blocker("https://agent.example.com", timeout=5, require_docker=False)
        self.assertEqual(blocker["kind"], "agent-unreachable")

    def test_write_blocked_report_uses_blocked_status_without_accuracy_summary(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            output = Path(tmpdir) / "blocked.json"
            write_blocked_report(
                str(output),
                {"dataset": "eval/official-eval.json"},
                {"kind": "docker-daemon-unavailable", "message": "Docker down"},
            )
            report = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual(report["status"], "blocked")
        self.assertEqual(report["summary"]["status"], "blocked")
        self.assertNotIn("answerAccuracyPct", report["summary"])


if __name__ == "__main__":
    unittest.main()
