#!/usr/bin/env python3
"""
Smoke / parity checks against a *running* Smart AC server — scenarios aligned with the Java
``ClientSpecificationE2EIntegrationTest``, ``DeviceReadingsRateLimitE2EIntegrationTest``,
``SmartAcEndToEndIntegrationTest``, and notification ingest behaviour.

Requires:
  - Server up (default http://127.0.0.1:8080)
  - Bootstrap admin (default ``admin@smartac.local`` / ``ChangeMe!Admin2024`` from application.properties)

Device tests use the public device API. Admin tests sign in via ``GET /login`` + ``POST /login``
(form + CSRF hidden field) then call ``/api/admin/**`` with the session cookie.

The server rejects ingest when any ``recordedAt`` is **after** the server's current time; synthetic
timestamps in this script are anchored in the past.

Why the admin dashboard can show devices with no "latest reading"
-----------------------------------------------------------------
1. **Different databases** — ``mvn test`` uses in-memory H2; your IDE/server usually uses MariaDB
   (see ``application.properties``). Data written during unit tests never appears in the browser
   unless that same JVM and datasource received the traffic.

2. **This script only talks to the URL you pass** — If ``--base-url`` points at localhost while
   the dashboard is another host, or vice versa, you will see mismatches.

3. **Several mirror scenarios never persist readings** — e.g. ``MIRROR-REG-*`` is register-only;
   ``MIRROR-EMP-*`` sends an empty ``readings`` array (expects HTTP 400); ``MIRROR-MAX-*`` sends
   one sample over the server max (expects 400). Those devices legitimately have no stored samples.

4. **Bulk async (HTTP 202)** — Large bulks are accepted and queued; samples appear after the
   background worker finishes. Reload the dashboard after a short wait.

The max-size bulk test (``MIRROR-B500-*``) uses ``build_demo_chart_readings``: smooth temperature
and humidity waves, low baseline CO, and several CO spikes above 9 PPM so admin line charts look
varied and the CO alert path still fires (max CO in the batch).

Examples:
  python scripts/integration/mirror_java_integration_tests.py --base-url http://127.0.0.1:8080
  python scripts/integration/mirror_java_integration_tests.py --base-url http://localhost:8080 --skip-admin
  python scripts/integration/mirror_java_integration_tests.py --skip-rate-limit-test
  python scripts/integration/mirror_java_integration_tests.py --skip-bulk-max --timeout 300
"""

from __future__ import annotations

import argparse
import http.cookiejar
import json
import math
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any, Callable


def _urljoin(base: str, path: str) -> str:
    return base.rstrip("/") + path


def _iso_z(dt: datetime) -> str:
    return dt.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _json_body(data: bytes) -> Any:
    if not data:
        return None
    try:
        return json.loads(data.decode("utf-8"))
    except json.JSONDecodeError:
        return data.decode("utf-8", errors="replace")


@dataclass
class HttpResult:
    status: int
    body: Any
    raw: bytes


class HttpClient:
    def __init__(self, base_url: str, timeout_s: float = 120.0):
        self.base_url = base_url.rstrip("/")
        self.timeout_s = timeout_s
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def request(
        self,
        method: str,
        path: str,
        *,
        data: bytes | None = None,
        headers: dict[str, str] | None = None,
    ) -> HttpResult:
        url = _urljoin(self.base_url, path)
        h = dict(headers or {})
        req = urllib.request.Request(url, data=data, headers=h, method=method)
        try:
            with self.opener.open(req, timeout=self.timeout_s) as resp:
                raw = resp.read()
                return HttpResult(resp.status, _json_body(raw), raw)
        except urllib.error.HTTPError as e:
            raw = e.read()
            return HttpResult(e.code, _json_body(raw), raw)


def _csrf_from_login_html(html: str) -> str:
    m = re.search(r'name="_csrf"\s+value="([^"]+)"', html)
    if not m:
        m = re.search(r"name='_csrf'\s+value='([^']+)'", html)
    if not m:
        raise RuntimeError("Could not find _csrf token in /login HTML (is this the Smart AC login page?)")
    return m.group(1)


class AdminSession:
    """Session cookie + CSRF form login (same as browser)."""

    def __init__(self, client: HttpClient, email: str, password: str):
        self.client = client
        r0 = client.request("GET", "/login")
        if r0.status != 200:
            raise RuntimeError(f"GET /login expected 200, got {r0.status}: {r0.body}")
        if not isinstance(r0.body, str):
            raise RuntimeError("GET /login expected HTML text body")
        token = _csrf_from_login_html(r0.body)
        form = (
            f"username={urllib.parse.quote(email, safe='')}"
            f"&password={urllib.parse.quote(password, safe='')}"
            f"&_csrf={urllib.parse.quote(token, safe='')}"
        )
        r1 = client.request(
            "POST",
            "/login",
            data=form.encode("utf-8"),
            headers={"Content-Type": "application/x-www-form-urlencoded"},
        )
        if r1.status not in (200, 302):
            raise RuntimeError(f"POST /login expected 200/302, got {r1.status}: {r1.body}")

    def get_json(self, path: str) -> HttpResult:
        return self.client.request("GET", path, headers={"Accept": "application/json"})

    def post_json(self, path: str, body: dict | None) -> HttpResult:
        data = json.dumps(body if body is not None else {}).encode("utf-8")
        return self.client.request(
            "POST",
            path,
            data=data,
            headers={"Content-Type": "application/json", "Accept": "application/json"},
        )


def register_device(client: HttpClient, serial: str, firmware: str = "1.0-mirror") -> dict[str, Any]:
    r = client.request(
        "POST",
        "/api/v1/devices/register",
        data=json.dumps({"serialNumber": serial, "firmwareVersion": firmware}).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    if r.status != 201:
        raise AssertionError(f"register expected 201, got {r.status}: {r.body}")
    assert isinstance(r.body, dict)
    return r.body


def post_readings(client: HttpClient, token: str, readings: list[dict]) -> HttpResult:
    return client.request(
        "POST",
        "/api/v1/devices/readings",
        data=json.dumps({"readings": readings}).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {token}",
        },
    )


def build_readings_json(count: int) -> list[dict]:
    now = datetime.now(timezone.utc).replace(microsecond=0)
    t0 = now - timedelta(seconds=max(count, 1) + 120)
    out = []
    for i in range(count):
        out.append(
            {
                "recordedAt": _iso_z(t0 + timedelta(seconds=i)),
                "temperatureCelsius": 20.0,
                "humidityPercent": 50.0,
                "carbonMonoxidePpm": 1.0,
                "healthStatus": "ok",
            }
        )
    return out


def build_demo_chart_readings(count: int) -> list[dict]:
    """
    Synthetic time series for admin charts: smooth temp/humidity waves, low CO with distinct
    spikes above 9 PPM so the CO series and notification rules are visually obvious.
    """
    if count < 1:
        return []
    # Spread samples across ~8.3 h (500 × 60 s), anchored in the past so the server accepts them.
    now = datetime.now(timezone.utc).replace(microsecond=0)
    t0 = now - timedelta(minutes=60 * max(count, 1) + 240)
    span = max(count - 1, 1)

    def co_spike_schedule(n: int) -> list[tuple[int, float]]:
        """Several indices with CO > 9; max in batch drives a single CO_THRESHOLD notification."""
        if n <= 2:
            return [(0, 10.5)]
        cand = sorted(
            {
                max(0, n // 12),
                max(0, n // 5),
                max(0, n // 3),
                max(0, (2 * n) // 5),
                max(0, n // 2),
                max(0, (3 * n) // 5),
                max(0, (4 * n) // 5),
                max(0, n - 8),
            }
        )
        ppm_cycle = (10.4, 12.8, 11.1, 15.6, 18.2, 13.0, 16.9, 10.7)
        return [(cand[k], ppm_cycle[k % len(ppm_cycle)]) for k in range(len(cand))]

    spikes = dict(co_spike_schedule(count))

    out: list[dict] = []
    for i in range(count):
        frac = i / span
        # Temperature: gentle daily curve + smaller ripple (visually smooth on line chart).
        temp_c = (
            21.0
            + 5.5 * math.sin(2 * math.pi * frac)
            + 1.4 * math.sin(2 * math.pi * (6.0 * frac + 0.2))
        )
        temp_c = round(max(14.0, min(32.0, temp_c)), 1)

        # Humidity: out-of-phase from temp, bounded for a plausible band.
        hum = (
            50.0
            + 20.0 * math.cos(2 * math.pi * frac + 0.55)
            + 6.0 * math.sin(2 * math.pi * (8.0 * frac))
        )
        hum = round(max(28.0, min(78.0, hum)), 1)

        if i in spikes:
            co = spikes[i]
        else:
            co = 0.65 + 0.45 * math.sin(2 * math.pi * 11 * frac) + 0.12 * (i % 7)
            co = round(max(0.25, min(3.2, co)), 2)

        out.append(
            {
                "recordedAt": _iso_z(t0 + timedelta(seconds=60 * i)),
                "temperatureCelsius": temp_c,
                "humidityPercent": hum,
                "carbonMonoxidePpm": co,
                "healthStatus": "ok",
            }
        )
    return out


def scenario_register_fields(client: HttpClient) -> None:
    serial = f"MIRROR-REG-{uuid.uuid4().hex[:10]}"
    d = register_device(client, serial, "3.2.1")
    assert d.get("serialNumber") == serial
    assert d.get("firmwareVersion") == "3.2.1"
    assert d.get("registrationDate")
    assert str(d.get("apiToken", "")).startswith("sac_")


def scenario_register_duplicate(client: HttpClient) -> None:
    serial = f"MIRROR-DUP-{uuid.uuid4().hex[:10]}"
    register_device(client, serial)
    r = client.request(
        "POST",
        "/api/v1/devices/register",
        data=json.dumps({"serialNumber": serial, "firmwareVersion": "1.0"}).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    assert r.status == 409, f"duplicate register expected 409, got {r.status}: {r.body}"


def scenario_readings_empty(client: HttpClient) -> None:
    serial = f"MIRROR-EMP-{uuid.uuid4().hex[:10]}"
    token = register_device(client, serial)["apiToken"]
    r = post_readings(client, str(token), [])
    assert r.status == 400, f"empty readings expected 400, got {r.status}: {r.body}"


def scenario_future_recorded_at_400(client: HttpClient) -> None:
    serial = f"MIRROR-FUT-{uuid.uuid4().hex[:10]}"
    token = register_device(client, serial)["apiToken"]
    future = datetime.now(timezone.utc).replace(microsecond=0) + timedelta(days=1)
    r = post_readings(
        client,
        str(token),
        [
            {
                "recordedAt": _iso_z(future),
                "temperatureCelsius": 20.0,
                "humidityPercent": 45.0,
                "carbonMonoxidePpm": 1.0,
                "healthStatus": "ok",
            }
        ],
    )
    assert r.status == 400, f"future recordedAt expected 400, got {r.status}: {r.body}"


def scenario_readings_over_max(client: HttpClient, max_samples: int) -> None:
    serial = f"MIRROR-MAX-{uuid.uuid4().hex[:10]}"
    token = register_device(client, serial)["apiToken"]
    r = post_readings(client, str(token), build_readings_json(max_samples + 1))
    assert r.status == 400, f">{max_samples} samples expected 400, got {r.status}: {r.body}"


def scenario_single_co_200(client: HttpClient) -> None:
    serial = f"MIRROR-CO-{uuid.uuid4().hex[:10]}"
    token = register_device(client, serial)["apiToken"]
    past = datetime.now(timezone.utc).replace(microsecond=0) - timedelta(hours=2)
    r = post_readings(
        client,
        str(token),
        [
            {
                "recordedAt": _iso_z(past),
                "temperatureCelsius": 22.0,
                "humidityPercent": 40.0,
                "carbonMonoxidePpm": 10.5,
                "healthStatus": "ok",
            }
        ],
    )
    assert r.status == 200, f"single-sample CO ingest expected 200, got {r.status}: {r.body}"


def scenario_bulk_async_202(client: HttpClient) -> None:
    serial = f"MIRROR-BULK-{uuid.uuid4().hex[:10]}"
    token = register_device(client, serial)["apiToken"]
    r = post_readings(client, str(token), build_readings_json(2))
    assert r.status == 202, f"two-sample bulk expected 202, got {r.status}: {r.body}"
    b = r.body if isinstance(r.body, dict) else {}
    assert b.get("acceptedSamples") == 2


def scenario_bulk_max_samples_202(client: HttpClient, sample_count: int) -> None:
    """
    POST one JSON body with ``sample_count`` sensor reading objects (default 500 = server max).
    Uses ``build_demo_chart_readings`` — varied temp/humidity waves and several CO spikes > 9 PPM
    for richer admin charts. Server returns 202 Accepted for multi-sample bulks.
    """
    serial = f"MIRROR-B500-{uuid.uuid4().hex[:10]}"
    token = register_device(client, serial)["apiToken"]
    readings = build_demo_chart_readings(sample_count)
    max_co = max(float(r["carbonMonoxidePpm"]) for r in readings)
    assert max_co > 9.0, f"demo series must include CO above 9 PPM for charts/alerts, got max={max_co}"

    payload = json.dumps({"readings": readings}).encode("utf-8")
    r = post_readings(client, str(token), readings)
    assert r.status == 202, (
        f"bulk {sample_count} samples expected 202, got {r.status}: {r.body!r} "
        f"(payload bytes={len(payload)}; raise --timeout if the server is slow)"
    )
    b = r.body if isinstance(r.body, dict) else {}
    assert b.get("acceptedSamples") == sample_count, f"acceptedSamples mismatch: {b!r}"
    assert b.get("queued") is True, f"expected queued async ingest, body={b!r}"


def scenario_rate_limit_429(client: HttpClient) -> None:
    """Passes if second POST returns 429; no failure if server has rate limit disabled (second 202)."""
    serial = f"MIRROR-RL-{uuid.uuid4().hex[:10]}"
    token = register_device(client, serial)["apiToken"]
    body = build_readings_json(2)
    r1 = post_readings(client, str(token), body)
    assert r1.status == 202, f"first bulk expected 202, got {r1.status}"
    r2 = post_readings(client, str(token), body)
    if r2.status == 429:
        return
    if r2.status == 202:
        print(
            "    [skip] rate-limit: second POST was 202 (server likely has readings-rate-limit-seconds=0)",
            file=sys.stderr,
        )
        return
    raise AssertionError(f"second bulk expected 429 or 202, got {r2.status}: {r2.body}")


def scenario_critical_health_ingest_no_notification_row(client: HttpClient, admin: AdminSession) -> None:
    """Health-only ingest is accepted but does not create admin notifications (CO-only policy)."""
    serial = f"MIRROR-HL-{uuid.uuid4().hex[:10]}"
    token = register_device(client, serial)["apiToken"]
    past = datetime.now(timezone.utc).replace(microsecond=0) - timedelta(hours=3)
    r = post_readings(
        client,
        str(token),
        [
            {
                "recordedAt": _iso_z(past),
                "temperatureCelsius": 20.0,
                "humidityPercent": 45.0,
                "carbonMonoxidePpm": 1.0,
                "healthStatus": "gas_leak",
            }
        ],
    )
    assert r.status == 200
    unr = admin.get_json("/api/admin/notifications/unresolved")
    assert unr.status == 200 and isinstance(unr.body, list)
    for n in unr.body:
        if isinstance(n, dict) and n.get("device", {}).get("serialNumber") == serial:
            raise AssertionError(
                f"expected no admin notification for health-only ingest, found type={n.get('type')!r}"
            )


def scenario_admin_devices_summary_search(admin: AdminSession, serial: str) -> None:
    q = urllib.parse.quote(serial, safe="")
    r = admin.get_json(f"/api/admin/devices/summary?q={q}")
    assert r.status == 200
    assert isinstance(r.body, dict)
    devs = r.body.get("devices") or []
    assert len(devs) >= 1
    assert devs[0].get("serialNumber") == serial


def scenario_admin_detail_and_series(admin: AdminSession, device_id: int) -> None:
    r = admin.get_json(f"/api/admin/devices/{device_id}/detail")
    assert r.status == 200
    assert isinstance(r.body, dict)
    assert r.body.get("device", {}).get("id") == device_id
    assert isinstance(r.body.get("recentReadings"), list)

    r2 = admin.get_json(f"/api/admin/devices/{device_id}/series?sensor=temperature&range=today")
    assert r2.status == 200
    assert isinstance(r2.body, list)


def scenario_admin_search_detail_series(device_client: HttpClient, admin: AdminSession) -> None:
    serial = f"MIRROR-FLOW-{uuid.uuid4().hex[:10]}"
    d = register_device(device_client, serial)
    did = int(d["deviceId"])
    token = str(d["apiToken"])
    t = datetime.now(timezone.utc).replace(microsecond=0) - timedelta(seconds=5)
    pr = post_readings(
        device_client,
        token,
        [
            {
                "recordedAt": _iso_z(t),
                "temperatureCelsius": 21.5,
                "humidityPercent": 55.0,
                "carbonMonoxidePpm": 1.2,
                "healthStatus": "ok",
            }
        ],
    )
    assert pr.status == 200
    scenario_admin_devices_summary_search(admin, serial)
    scenario_admin_detail_and_series(admin, did)


def scenario_admin_simulate_and_resolve(device_client: HttpClient, admin: AdminSession) -> None:
    serial = f"MIRROR-SIM-{uuid.uuid4().hex[:10]}"
    d2 = register_device(device_client, serial)
    did2 = int(d2["deviceId"])
    sim = admin.post_json(f"/api/admin/devices/{did2}/simulate-notifications", None)
    assert sim.status == 200
    unr = admin.get_json("/api/admin/notifications/unresolved")
    assert unr.status == 200 and isinstance(unr.body, list)
    nid = None
    for n in unr.body:
        if isinstance(n, dict) and n.get("device", {}).get("serialNumber") == serial:
            nid = n.get("id")
            break
    assert nid is not None, "simulate should create at least one unresolved notification for device"
    res = admin.post_json(f"/api/admin/notifications/{int(nid)}/resolve", {})
    assert res.status == 200, f"resolve expected 200, got {res.status}: {res.body}"


def run_all(args: argparse.Namespace) -> int:
    device_client = HttpClient(args.base_url, timeout_s=args.timeout)
    failures: list[str] = []

    def run(name: str, fn: Callable[[], None]) -> None:
        try:
            fn()
            print(f"OK  {name}")
        except Exception as e:
            failures.append(f"{name}: {e}")
            print(f"FAIL {name}: {e}")

    run("register_fields", lambda: scenario_register_fields(device_client))
    run("register_duplicate_409", lambda: scenario_register_duplicate(device_client))
    run("readings_empty_400", lambda: scenario_readings_empty(device_client))
    run("readings_future_recorded_at_400", lambda: scenario_future_recorded_at_400(device_client))
    run("readings_over_max_400", lambda: scenario_readings_over_max(device_client, args.max_samples))
    run("bulk_two_samples_202", lambda: scenario_bulk_async_202(device_client))
    if not args.skip_bulk_max:
        run(
            f"bulk_{args.bulk_sample_count}_samples_202",
            lambda: scenario_bulk_max_samples_202(device_client, args.bulk_sample_count),
        )
    run("single_co_ingest_200", lambda: scenario_single_co_200(device_client))

    if not args.skip_rate_limit_test:
        run("rate_limit_second_429_or_skip", lambda: scenario_rate_limit_429(device_client))

    if not args.skip_admin:
        admin_client = HttpClient(args.base_url, timeout_s=args.timeout)
        try:
            admin = AdminSession(admin_client, args.admin_email, args.admin_password)
        except Exception as e:
            print(f"FAIL admin_login: {e}", file=sys.stderr)
            return 1

        run("admin_search_detail_series", lambda: scenario_admin_search_detail_series(device_client, admin))
        run(
            "critical_health_ingest_no_notification",
            lambda: scenario_critical_health_ingest_no_notification_row(device_client, admin),
        )

        r = admin.get_json("/api/admin/admins")
        assert r.status == 200 and isinstance(r.body, list)
        print(f"OK  admin_list_admins (count={len(r.body)})")

        inv = admin.post_json("/api/admin/invitations", {"emailHint": "invitee@example.com"})
        assert inv.status == 200 and isinstance(inv.body, dict) and inv.body.get("inviteLink")
        print("OK  admin_create_invitation")

        r_dash = admin.get_json("/api/admin/dashboard-state?size=10")
        assert r_dash.status == 200
        print("OK  admin_dashboard_state")

        run("admin_simulate_notifications_and_resolve", lambda: scenario_admin_simulate_and_resolve(device_client, admin))

    if failures:
        for f in failures:
            print(f, file=sys.stderr)
        return 1
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base-url", default="http://127.0.0.1:8080")
    ap.add_argument("--admin-email", default="admin@smartac.local")
    ap.add_argument("--admin-password", default="ChangeMe!Admin2024")
    ap.add_argument("--max-samples", type=int, default=500, help="Must match server max (default 500)")
    ap.add_argument(
        "--bulk-sample-count",
        type=int,
        default=500,
        help="How many reading objects in the max-size bulk JSON test (default 500; must be <= --max-samples)",
    )
    ap.add_argument("--timeout", type=float, default=120.0)
    ap.add_argument("--skip-admin", action="store_true", help="Only device API checks (no /login)")
    ap.add_argument("--skip-rate-limit-test", action="store_true")
    ap.add_argument(
        "--skip-bulk-max",
        action="store_true",
        help="Skip the large bulk POST (many JSON objects); use on very slow links",
    )
    args = ap.parse_args()
    if not args.skip_bulk_max:
        if args.bulk_sample_count < 2 or args.bulk_sample_count > args.max_samples:
            print(
                "Invalid --bulk-sample-count: must satisfy 2 <= count <= --max-samples "
                f"(got bulk_sample_count={args.bulk_sample_count}, max_samples={args.max_samples}).",
                file=sys.stderr,
            )
            return 2
    return run_all(args)


if __name__ == "__main__":
    sys.exit(main())
