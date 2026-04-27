#!/usr/bin/env python3
"""
API checks against a running Smart AC server (device register + readings ingest).

Requires:
  - Server listening (default http://127.0.0.1:8080)
  - app.device-ingest.readings-rate-limit-seconds > 0 for test_rate_limit (default 60)

Run:
  python scripts/integration/device_readings_unit_tests.py
  python scripts/integration/device_readings_unit_tests.py --base-url http://localhost:8080

Or with unittest discovery:
  python -m unittest scripts.integration.device_readings_unit_tests -v
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import unittest
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any


def _urljoin(base: str, path: str) -> str:
    return base.rstrip("/") + path


def _iso_z(dt: datetime) -> str:
    return dt.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _post_json(url: str, body: dict, headers: dict | None = None) -> tuple[int, Any]:
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        method="POST",
        headers={
            "Content-Type": "application/json",
            **(headers or {}),
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            raw = resp.read().decode("utf-8")
            status = resp.status
            try:
                parsed = json.loads(raw) if raw else None
            except json.JSONDecodeError:
                parsed = raw
            return status, parsed
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            parsed = json.loads(raw) if raw else None
        except json.JSONDecodeError:
            parsed = raw
        return e.code, parsed


@dataclass
class RegisteredDevice:
    serial: str
    token: str


def register_device(base_url: str, serial: str) -> RegisteredDevice:
    url = _urljoin(base_url, "/api/v1/devices/register")
    status, body = _post_json(
        url,
        {"serialNumber": serial, "firmwareVersion": "1.0-pytest"},
    )
    if status != 201:
        raise AssertionError(f"register expected 201, got {status}: {body}")
    if not isinstance(body, dict) or "apiToken" not in body:
        raise AssertionError(f"register missing apiToken: {body}")
    return RegisteredDevice(serial=serial, token=str(body["apiToken"]))


def post_readings(base_url: str, token: str, readings: list[dict]) -> tuple[int, Any]:
    url = _urljoin(base_url, "/api/v1/devices/readings")
    return _post_json(
        url,
        {"readings": readings},
        headers={"Authorization": f"Bearer {token}"},
    )


def past_anchor(*, days_ago: float = 7) -> datetime:
    """UTC instant strictly in the past (no future recordedAt in tests)."""
    return (datetime.now(timezone.utc) - timedelta(days=days_ago)).replace(microsecond=0)


def sample_reading(anchor: datetime, offset_seconds: int = 0) -> dict:
    t = anchor + timedelta(seconds=offset_seconds)
    return {
        "recordedAt": _iso_z(t),
        "temperatureCelsius": 21.0,
        "humidityPercent": 50.0,
        "carbonMonoxidePpm": 1.0,
        "healthStatus": "ok",
    }


class DeviceReadingsApiTests(unittest.TestCase):
    """Integration-style tests; hit a live server (see module docstring)."""

    # Overridden in __main__ when using --base-url; else default:
    base_url = os.environ.get("SMARTAC_BASE_URL", "http://127.0.0.1:8080")

    def test_one_reading(self) -> None:
        serial = f"PY-1-{uuid.uuid4().hex[:12]}"
        dev = register_device(self.base_url, serial)
        anchor = past_anchor(days_ago=7)
        status, body = post_readings(self.base_url, dev.token, [sample_reading(anchor)])
        self.assertEqual(status, 200, body)
        self.assertIsInstance(body, dict)
        self.assertEqual(body.get("acceptedSamples"), 1)
        self.assertIsNone(body.get("queued"), body)

    def test_five_hundred_readings(self) -> None:
        serial = f"PY-500-{uuid.uuid4().hex[:12]}"
        dev = register_device(self.base_url, serial)
        # 500 samples span 499s; stay in the past even near UTC midnight.
        anchor = past_anchor(days_ago=8) - timedelta(seconds=600)
        readings = [sample_reading(anchor, i) for i in range(500)]
        status, body = post_readings(self.base_url, dev.token, readings)
        self.assertEqual(status, 202, body)
        self.assertIsInstance(body, dict)
        self.assertEqual(body.get("acceptedSamples"), 500)
        self.assertEqual(body.get("queued"), True)

    def test_rate_limit_second_request_within_60_seconds(self) -> None:
        serial = f"PY-RL-{uuid.uuid4().hex[:12]}"
        dev = register_device(self.base_url, serial)
        anchor = past_anchor(days_ago=9)

        first_status, first_body = post_readings(
            self.base_url, dev.token, [sample_reading(anchor)]
        )
        self.assertEqual(first_status, 200, first_body)

        second_status, second_body = post_readings(
            self.base_url, dev.token, [sample_reading(anchor, offset_seconds=1)]
        )
        self.assertEqual(
            second_status,
            429,
            f"expected 429 when second POST is within rate-limit window; got {second_status}: {second_body}",
        )
        self.assertIsInstance(second_body, dict)
        self.assertIn("error", second_body)


def _parse_script_and_unittest_args(argv: list[str]) -> tuple[argparse.Namespace, list[str]]:
    """Let `--base-url` be handled here; pass remaining flags to unittest (e.g. `-v`, `-k one`)."""
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument(
        "--base-url",
        default=os.environ.get("SMARTAC_BASE_URL", "http://127.0.0.1:8080"),
        help="Server root URL (or set SMARTAC_BASE_URL)",
    )
    return p.parse_known_args(argv)


if __name__ == "__main__":
    args, unittest_argv = _parse_script_and_unittest_args(sys.argv[1:])
    DeviceReadingsApiTests.base_url = args.base_url
    os.environ["SMARTAC_BASE_URL"] = args.base_url
    unittest.main(argv=[sys.argv[0], "-v", *unittest_argv])
