#!/usr/bin/env python3
"""
Simulate N devices as N long-lived threads: each registers once, then loops forever:
  — optional steady single-sample uploads (respects server ~55s spacing),
  — "internet down": sleep with NO HTTP (outage),
  — bulk catch-up POST with one sample per simulated *minute* of outage (capped), not one per second.

Bulk row count is ceil(outage_seconds / 60), capped by --max-backlog-minutes. Example: default outage
60-180s → 1-3 rows per bulk (not 60-180 rows). Use --outage-max-sec 300 for up to 5 rows, etc.

Single-sample (normal) /readings uploads: --steady-before-outage N and/or --steady-after-bulk M (each
row spaced ~65s to satisfy server single-sample rate limits). Bulk catch-up is unchanged.

Web app notification rules (Spring + admin UI — the script can prove them against a running server):

  1) Carbon monoxide over 9 PPM → admin notification (nav badge + /admin/notifications).
  2) Admin can mark a notification resolved; it then disappears for all admins (unresolved list / count).
  3) Health status needs_service, needs_new_filter, or gas_leak → same style of notification.

How to exercise that from this script:

  • End-to-end API demo ONLY (register device, ingest CO + each health keyword; you resolve in the
    web UI unless you pass --auto-resolve-notifications for API resolve + assertions):
      python scripts/utils/simulate_multi_devices.py --demo-admin-notifications \\
        --admin-email admin@smartac.local --admin-password 'ChangeMe!Admin2024'
    (With --test as well, the demo flag is skipped; --test already runs the same notification checks.)

  • Full self-test bundle (device API + same notification demo above):
      python scripts/utils/simulate_multi_devices.py --test \\
        --admin-email admin@smartac.local --admin-password 'ChangeMe!Admin2024'
    (or set SMARTAC_ADMIN_EMAIL / SMARTAC_ADMIN_PASSWORD)

  • Long-running fleet traffic that repeatedly sends CO>9 + rotating health on ingests:
      python scripts/utils/simulate_multi_devices.py --sim-admin-notifications --threads 3

The long-run simulator defaults to safe CO/health so load tests do not spam admins; use
--inject-sim-alert / --sim-admin-notifications for alert-shaped payloads. Nav badge polls ~15s;
/admin/notifications HTML does not auto-refresh.

Requires: Python 3.9+ (stdlib only).

Usage:
  python scripts/utils/simulate_multi_devices.py --internet-down-100
    # 100 devices: every thread only simulates internet-down (sleep, no HTTP) then bulk catch-up; no steady uploads.
  python scripts/utils/simulate_multi_devices.py
    # Same as above: default is 100 threads and steady-before-outage=0 (outage + bulk only).
  python scripts/utils/simulate_multi_devices.py --threads 50 --steady-before-outage 0
  python scripts/utils/simulate_multi_devices.py --threads 100 --outage-min-sec 90 --outage-max-sec 180
  python scripts/utils/simulate_multi_devices.py --base-url http://127.0.0.1:8080

Optional one-shot stress (all threads POST bulk together after a Barrier):
  python scripts/utils/simulate_multi_devices.py --burst-once --threads 100

Automated device API checks (exit 0 = all passed, 1 = failure):
  python scripts/utils/simulate_multi_devices.py --test
  python scripts/utils/simulate_multi_devices.py --test --base-url http://127.0.0.1:8080
  python scripts/utils/simulate_multi_devices.py --test --test-burst --admin-email … --admin-password …
  # --test-burst: barrier load + each bulk includes CO>9 PPM and gas_leak/needs_new_filter/needs_service; admin API verifies notifications

Admin notification checks (same as --demo-admin-notifications, but after full --test device checks):
  python scripts/utils/simulate_multi_devices.py --test --admin-email admin@smartac.local --admin-password 'ChangeMe!Admin2024'
  # Add --auto-resolve-notifications to resolve via API instead of clicking Resolve in the browser.

Ctrl+C stops all threads. Run the Spring Boot app first.
"""

from __future__ import annotations

import argparse
import http.cookiejar
import json
import os
import random
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional

# Long-run simulator: 100 devices, each cycles outage (no HTTP) + bulk backlog (no steady single-sample phase).
DEFAULT_THREADS = 100
DEFAULT_STEADY_BEFORE_OUTAGE = 0
# Seconds between single-sample uploads (server min steady ~55s)
STEADY_INTERVAL_SEC = 65
# Match server NotificationService: CO_ALERT_THRESHOLD > 9, critical health keywords
SIM_ALERT_CO_PPM = 10.5
SIM_ALERT_HEALTH_KEYWORDS = ("gas_leak", "needs_new_filter", "needs_service")
# After a bulk, wait before starting next outage cycle (server bulk spacing ~2s; stay safe)
INTER_CYCLE_SEC = 5
HTTP_TIMEOUT_SEC = 180


def utc_iso(dt: datetime) -> str:
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")


def post_json(
    url: str,
    body: dict,
    headers: Optional[Dict[str, str]] = None,
    timeout: int = HTTP_TIMEOUT_SEC,
    extra_headers: Optional[Dict[str, str]] = None,
) -> tuple[int, str]:
    data = json.dumps(body).encode("utf-8")
    hdr = {"Content-Type": "application/json; charset=utf-8", **(headers or {})}
    if extra_headers:
        hdr.update(extra_headers)
    req = urllib.request.Request(url, data=data, method="POST", headers=hdr)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")


def backlog_minutes_for_outage(outage_sec: int, cap_minutes: int) -> int:
    """Wall minutes to cover with one sample per minute (partial minute rounds up)."""
    mins = max(1, (max(0, int(outage_sec)) + 59) // 60)
    return min(max(1, cap_minutes), mins)


def build_bulk_from_outage_minutes(backlog_mins: int) -> dict[str, Any]:
    """One reading per minute of simulated backlog (internet was down)."""
    backlog_mins = max(1, min(backlog_mins, 500))
    end = datetime.now(timezone.utc)
    start = end - timedelta(minutes=backlog_mins - 1)
    rows: List[dict[str, Any]] = []
    for i in range(backlog_mins):
        at = start + timedelta(minutes=i)
        rows.append(
            {
                "recordedAt": utc_iso(at),
                "temperatureCelsius": round(20.5 + random.uniform(-2, 2), 1),
                "humidityPercent": round(43.0 + random.uniform(-4, 4), 1),
                "carbonMonoxidePpm": round(2.0 + random.uniform(0, 2), 1),
                "healthStatus": "ok",
            }
        )
    return {"readings": rows}


def sim_alert_health_keyword(worker_id: int, cycle: int, salt: str) -> str:
    idx = (worker_id + cycle + sum(ord(c) for c in salt)) % len(SIM_ALERT_HEALTH_KEYWORDS)
    return SIM_ALERT_HEALTH_KEYWORDS[idx]


def apply_admin_notify_reading_row(row: dict[str, Any], worker_id: int, cycle: int, salt: str) -> None:
    """CO > 9 PPM + one critical health keyword (same rules as server NotificationService)."""
    row["carbonMonoxidePpm"] = SIM_ALERT_CO_PPM
    row["healthStatus"] = sim_alert_health_keyword(worker_id, cycle, salt)


def tail_reading_inject_sim_alert(readings: List[dict[str, Any]], worker_id: int, cycle: int) -> None:
    """Last bulk row: triggers CO_THRESHOLD + HEALTH_KEYWORD admin notifications."""
    if not readings:
        return
    apply_admin_notify_reading_row(readings[-1], worker_id, cycle, "bulk")


def build_bulk_fixed_samples(num_samples: int) -> dict[str, Any]:
    """Exactly num_samples rows spaced one minute apart (for --burst-once)."""
    num_samples = max(2, min(num_samples, 500))
    end = datetime.now(timezone.utc)
    start = end - timedelta(minutes=num_samples - 1)
    rows: List[dict[str, Any]] = []
    for i in range(num_samples):
        at = start + timedelta(minutes=i)
        rows.append(
            {
                "recordedAt": utc_iso(at),
                "temperatureCelsius": round(20.5 + random.uniform(-2, 2), 1),
                "humidityPercent": round(43.0 + random.uniform(-4, 4), 1),
                "carbonMonoxidePpm": round(2.0 + random.uniform(0, 2), 1),
                "healthStatus": "ok",
            }
        )
    return {"readings": rows}


def build_bulk_burst_with_alerts(num_samples: int) -> dict[str, Any]:
    """
    Bulk for stress burst self-test: row 0 has CO > 9 PPM; rows 1–3 carry gas_leak,
    needs_new_filter, needs_service (one ingest → CO_THRESHOLD + up to 3 HEALTH_KEYWORD alerts).
    """
    num_samples = max(4, min(num_samples, 500))
    end = datetime.now(timezone.utc)
    start = end - timedelta(minutes=num_samples - 1)
    rows: List[dict[str, Any]] = []
    for i in range(num_samples):
        at = start + timedelta(minutes=i)
        if i == 0:
            co, health = 10.5, "ok"
        elif i == 1:
            co, health = 1.0, "gas_leak"
        elif i == 2:
            co, health = 1.0, "needs_new_filter"
        elif i == 3:
            co, health = 1.0, "needs_service"
        else:
            co, health = 2.0, "ok"
        rows.append(
            {
                "recordedAt": utc_iso(at),
                "temperatureCelsius": round(20.5 + random.uniform(-2, 2), 1),
                "humidityPercent": round(43.0 + random.uniform(-4, 4), 1),
                "carbonMonoxidePpm": co,
                "healthStatus": health,
            }
        )
    return {"readings": rows}


def ping(base_url: str) -> None:
    url = f"{base_url.rstrip('/')}/login"
    req = urllib.request.Request(url, method="GET")
    with urllib.request.urlopen(req, timeout=5) as resp:
        if resp.status != 200:
            raise RuntimeError(f"GET {url} -> {resp.status}")


def sleep_interruptible(seconds: int, stop: threading.Event) -> None:
    for _ in range(max(0, seconds)):
        if stop.is_set():
            return
        time.sleep(1)


def post_one_sample_reading(
    readings_url: str,
    auth: Dict[str, str],
    *,
    admin_notify_sample: bool = False,
    worker_id: int = 0,
    cycle: int = 0,
    phase: str = "",
) -> tuple[int, str]:
    """POST /readings with one row (normal steady upload). Optional admin_notify_sample = CO>9 + critical health."""
    row: dict[str, Any] = {
        "recordedAt": utc_iso(datetime.now(timezone.utc)),
        "temperatureCelsius": round(21.0 + random.uniform(-1, 1), 1),
        "humidityPercent": round(44.0 + random.uniform(-3, 3), 1),
        "carbonMonoxidePpm": round(2.5 + random.uniform(0, 1.5), 1),
        "healthStatus": "ok",
    }
    if admin_notify_sample:
        apply_admin_notify_reading_row(row, worker_id, cycle, phase or "steady")
    body = {"readings": [row]}
    return post_json(readings_url, body, auth)


def run_steady_sample_rounds(
    readings_url: str,
    auth: Dict[str, str],
    stop: threading.Event,
    worker_id: int,
    cycle: int,
    count: int,
    phase: str,
    inject_sim_alert: bool,
) -> None:
    """Send `count` single-sample POSTs, ~STEADY_INTERVAL_SEC apart (server rate limit)."""
    for i in range(max(0, count)):
        if stop.is_set():
            return
        last = inject_sim_alert and (i == max(0, count) - 1)
        c, t = post_one_sample_reading(
            readings_url,
            auth,
            admin_notify_sample=last,
            worker_id=worker_id,
            cycle=cycle,
            phase=phase,
        )
        if c == 200:
            tag = " (admin CO/health)" if last else ""
            print(f"[{worker_id}] single-sample ok ({phase}) {i + 1}/{count} cycle={cycle}{tag}")
        elif c == 429:
            print(f"[{worker_id}] 429 single-sample ({phase}), sleep {STEADY_INTERVAL_SEC}s")
            sleep_interruptible(STEADY_INTERVAL_SEC, stop)
            continue
        else:
            print(f"[{worker_id}] single-sample HTTP {c} ({phase}): {t[:200]}")
        sleep_interruptible(STEADY_INTERVAL_SEC, stop)


def outage_worker(
    worker_id: int,
    base_url: str,
    stop: threading.Event,
    outage_min_sec: int,
    outage_max_sec: int,
    max_backlog_minutes: int,
    steady_before_outage: int,
    steady_after_bulk: int,
    inter_cycle_sec: int,
    inject_sim_alert: bool,
) -> None:
    register_url = f"{base_url.rstrip('/')}/api/v1/devices/register"
    readings_url = f"{base_url.rstrip('/')}/api/v1/devices/readings"

    token: Optional[str] = None
    serial = f"PYSIM-{worker_id}-{uuid.uuid4().hex[:8]}"
    while not stop.is_set() and token is None:
        reg_body = {"serialNumber": serial, "firmwareVersion": f"py-sim-{worker_id}.0.0"}
        code, text = post_json(register_url, reg_body)
        if code == 201:
            reg = json.loads(text)
            token = reg["apiToken"]
            print(f"[{worker_id}] registered serial={serial} deviceId={reg.get('deviceId')}")
            break
        print(f"[{worker_id}] register HTTP {code}, retry in 10s: {text[:200]}")
        sleep_interruptible(10, stop)

    if token is None:
        return

    auth = {"Authorization": f"Bearer {token}"}
    cycle = 0
    lo, hi = min(outage_min_sec, outage_max_sec), max(outage_min_sec, outage_max_sec)

    while not stop.is_set():
        cycle += 1

        run_steady_sample_rounds(
            readings_url,
            auth,
            stop,
            worker_id,
            cycle,
            steady_before_outage,
            "before-outage",
            inject_sim_alert,
        )
        if stop.is_set():
            return

        outage_len = random.randint(lo, hi)
        print(f"[{worker_id}] internet DOWN {outage_len}s (no HTTP) cycle={cycle}")
        sleep_interruptible(outage_len, stop)
        if stop.is_set():
            return

        backlog_mins = backlog_minutes_for_outage(outage_len, max_backlog_minutes)
        bulk = build_bulk_from_outage_minutes(backlog_mins)
        if inject_sim_alert:
            tail_reading_inject_sim_alert(bulk["readings"], worker_id, cycle)
        sent = len(bulk["readings"])
        c, t = post_json(readings_url, bulk, auth)
        if c in (200, 202):
            try:
                j = json.loads(t)
                print(
                    f"[{worker_id}] bulk OK HTTP {c} bulk_sent={sent} acceptedSamples={j.get('acceptedSamples')} "
                    f"queued={j.get('queued')} backlog_mins={backlog_mins} outage_s={outage_len}"
                )
            except json.JSONDecodeError:
                print(f"[{worker_id}] bulk HTTP {c}: {t[:200]}")
        elif c == 429:
            print(f"[{worker_id}] bulk 429, sleep 30s")
            sleep_interruptible(30, stop)
            continue
        elif c == 503:
            print(f"[{worker_id}] bulk 503 overloaded, sleep 15s")
            sleep_interruptible(15, stop)
            continue
        else:
            print(f"[{worker_id}] bulk HTTP {c}: {t[:300]}")

        sleep_interruptible(inter_cycle_sec, stop)
        if stop.is_set():
            return

        run_steady_sample_rounds(
            readings_url,
            auth,
            stop,
            worker_id,
            cycle,
            steady_after_bulk,
            "after-bulk",
            inject_sim_alert,
        )


# --- automated self-tests (device API) ---


class TestError(Exception):
    """Raised when a --test assertion fails."""


def require(cond: bool, message: str) -> None:
    if not cond:
        raise TestError(message)


def admin_login_opener(base: str, email: str, password: str) -> urllib.request.OpenerDirector:
    """Session cookie jar + form login (CSRF from /login)."""
    base = base.rstrip("/")
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    login_page = urllib.request.Request(f"{base}/login", method="GET")
    with opener.open(login_page, timeout=30) as r:
        html = r.read().decode("utf-8", errors="replace")
    m = re.search(r'name="_csrf"\s+value="([^"]+)"', html)
    if not m:
        raise TestError("admin login: could not find _csrf hidden field on /login")
    csrf = m.group(1)
    form = urllib.parse.urlencode(
        {"username": email, "password": password, "_csrf": csrf}
    ).encode("utf-8")
    post = urllib.request.Request(
        f"{base}/login",
        data=form,
        method="POST",
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    opener.open(post, timeout=30)
    ping_count = urllib.request.Request(f"{base}/api/admin/notifications/open-count")
    try:
        with opener.open(ping_count, timeout=30) as r:
            if r.status != 200:
                raise TestError(f"admin session: open-count HTTP {r.status} (bad credentials?)")
    except urllib.error.HTTPError as e:
        raise TestError(f"admin session: open-count HTTP {e.code} (login failed?)") from e
    return opener


def admin_open_count(opener: urllib.request.OpenerDirector, base: str) -> int:
    base = base.rstrip("/")
    req = urllib.request.Request(f"{base}/api/admin/notifications/open-count")
    with opener.open(req, timeout=30) as r:
        data = json.loads(r.read().decode("utf-8", errors="replace"))
    return int(data["count"])


def admin_unresolved(opener: urllib.request.OpenerDirector, base: str) -> List[dict[str, Any]]:
    base = base.rstrip("/")
    req = urllib.request.Request(f"{base}/api/admin/notifications/unresolved")
    with opener.open(req, timeout=30) as r:
        return json.loads(r.read().decode("utf-8", errors="replace"))


def admin_resolve(opener: urllib.request.OpenerDirector, base: str, notification_id: int) -> None:
    base = base.rstrip("/")
    url = f"{base}/api/admin/notifications/{notification_id}/resolve"
    req = urllib.request.Request(url, method="POST", data=b"")
    with opener.open(req, timeout=30) as r:
        require(r.status in (200, 204), f"resolve POST expected 200/204, got {r.status}")


def poll_until(timeout_sec: float, interval_sec: float, desc: str, fn) -> None:
    deadline = time.monotonic() + timeout_sec
    last_exc: Optional[Exception] = None
    while time.monotonic() < deadline:
        try:
            if fn():
                return
        except Exception as e:
            last_exc = e
        time.sleep(interval_sec)
    msg = f"timeout ({timeout_sec}s): {desc}"
    if last_exc:
        msg += f" — last error: {last_exc}"
    raise TestError(msg)


def run_admin_notification_tests(
    base: str,
    email: str,
    password: str,
    *,
    auto_resolve: bool = False,
) -> None:
    """
    Implements the admin notification product spec via HTTP (same rules as the web app):

    - CO over 9 PPM on ingest → CO_THRESHOLD row in GET /api/admin/notifications/unresolved.
    - Health needs_service, gas_leak, needs_new_filter each produce HEALTH_KEYWORD rows.

    By default does NOT call resolve (use the admin web UI “Resolve” button). Pass
    auto_resolve=True (CLI --auto-resolve-notifications) to POST resolve and assert rows
    disappear from /unresolved (for CI).
    """
    opener = admin_login_opener(base, email, password)
    print("[test] admin session OK (/api/admin/notifications/open-count)")

    register_url = f"{base.rstrip('/')}/api/v1/devices/register"
    readings_url = f"{base.rstrip('/')}/api/v1/devices/readings"
    serial = f"PYNOTIFY-{uuid.uuid4().hex[:10]}"
    code, text = post_json(register_url, {"serialNumber": serial, "firmwareVersion": "notify-1.0"})
    require(code == 201, f"notify device register: {code} {text[:300]}")
    token = json.loads(text)["apiToken"]
    auth = {"Authorization": f"Bearer {token}"}

    open_before = admin_open_count(opener, base)
    t0 = datetime.now(timezone.utc)

    def co_row(off: int, co: float, health: str = "ok") -> dict[str, Any]:
        at = t0 + timedelta(minutes=off)
        return {
            "recordedAt": utc_iso(at),
            "temperatureCelsius": 22.0,
            "humidityPercent": 40.0,
            "carbonMonoxidePpm": co,
            "healthStatus": health,
        }

    co_bulk = {"readings": [co_row(600, 10.5), co_row(601, 1.0)]}
    c, t = post_json(readings_url, co_bulk, auth)
    require(c in (200, 202), f"admin CO bulk ingest: {c} {t[:300]}")

    co_id_holder: dict[str, Optional[int]] = {"id": None}

    def found_co_notification() -> bool:
        for n in admin_unresolved(opener, base):
            if n.get("type") != "CO_THRESHOLD":
                continue
            msg = str(n.get("message") or "")
            if serial in msg:
                co_id_holder["id"] = int(n["id"])
                return True
        return False

    poll_until(25.0, 0.5, "CO_THRESHOLD notification for new device", found_co_notification)
    nid = co_id_holder["id"]
    require(nid is not None, "CO notification id missing")
    open_mid = admin_open_count(opener, base)
    require(open_mid >= open_before, "open count should not drop before resolve")
    print(f"[test] admin sees CO_THRESHOLD notification id={nid} (serial in message)")
    if auto_resolve:
        admin_resolve(opener, base, nid)
        ids_after = {int(n["id"]) for n in admin_unresolved(opener, base)}
        require(nid not in ids_after, f"resolved CO notification id={nid} still in unresolved list")
        print("[test] resolve CO notification — it no longer appears in /unresolved")
    else:
        print(
            f"[test] Resolve CO in browser: {base}/admin/notifications (id={nid}) — "
            "or re-run with --auto-resolve-notifications for API resolve checks"
        )

    time.sleep(3)
    svc_bulk = {
        "readings": [
            co_row(720, 1.0, "needs_service"),
            co_row(721, 1.0, "ok"),
        ]
    }
    c_s, t_s = post_json(readings_url, svc_bulk, auth)
    require(c_s in (200, 202), f"needs_service bulk: {c_s} {t_s[:300]}")
    sid_holder: dict[str, Optional[int]] = {"id": None}

    def found_svc() -> bool:
        for n in admin_unresolved(opener, base):
            if n.get("type") != "HEALTH_KEYWORD":
                continue
            if str(n.get("healthKeyword") or "").lower() != "needs_service":
                continue
            if serial in str(n.get("message") or ""):
                sid_holder["id"] = int(n["id"])
                return True
        return False

    poll_until(25.0, 0.5, "HEALTH_KEYWORD needs_service", found_svc)
    sid = sid_holder["id"]
    require(sid is not None, "needs_service id missing")
    if auto_resolve:
        admin_resolve(opener, base, sid)
        require(
            sid not in {int(n["id"]) for n in admin_unresolved(opener, base)},
            f"resolved needs_service id={sid} still in unresolved list",
        )
        print("[test] needs_service notification + resolve OK")
    else:
        print(
            f"[test] Resolve needs_service in browser: {base}/admin/notifications (id={sid}) "
            "or use --auto-resolve-notifications"
        )

    time.sleep(3)
    health_bulk = {
        "readings": [
            co_row(900, 1.0, "gas_leak"),
            co_row(901, 1.0, "ok"),
        ]
    }
    c2, t2 = post_json(readings_url, health_bulk, auth)
    require(c2 in (200, 202), f"admin health bulk: {c2} {t2[:300]}")

    hid_holder: dict[str, Optional[int]] = {"id": None}

    def found_health_notification() -> bool:
        for n in admin_unresolved(opener, base):
            if n.get("type") != "HEALTH_KEYWORD":
                continue
            if str(n.get("healthKeyword") or "").lower() != "gas_leak":
                continue
            msg = str(n.get("message") or "")
            if serial in msg:
                hid_holder["id"] = int(n["id"])
                return True
        return False

    poll_until(25.0, 0.5, "HEALTH_KEYWORD gas_leak notification", found_health_notification)
    hid = hid_holder["id"]
    require(hid is not None, "health notification id missing")
    print(f"[test] admin sees HEALTH_KEYWORD gas_leak id={hid}")
    if auto_resolve:
        admin_resolve(opener, base, hid)
        ids_final = {int(n["id"]) for n in admin_unresolved(opener, base)}
        require(hid not in ids_final, f"resolved health id={hid} still in unresolved")
        print("[test] resolve health notification — it no longer appears in /unresolved")
    else:
        print(
            f"[test] Resolve gas_leak in browser: {base}/admin/notifications (id={hid}) "
            "or use --auto-resolve-notifications"
        )

    time.sleep(3)
    nk_bulk = {
        "readings": [
            co_row(1200, 1.0, "needs_new_filter"),
            co_row(1201, 1.0, "ok"),
        ]
    }
    c3, t3 = post_json(readings_url, nk_bulk, auth)
    require(c3 in (200, 202), f"needs_new_filter bulk: {c3} {t3[:300]}")
    nk_id: dict[str, Optional[int]] = {"id": None}

    def found_nk() -> bool:
        for n in admin_unresolved(opener, base):
            if n.get("type") != "HEALTH_KEYWORD":
                continue
            if str(n.get("healthKeyword") or "").lower() != "needs_new_filter":
                continue
            if serial in str(n.get("message") or ""):
                nk_id["id"] = int(n["id"])
                return True
        return False

    poll_until(25.0, 0.5, "HEALTH_KEYWORD needs_new_filter", found_nk)
    kid = nk_id["id"]
    require(kid is not None, "needs_new_filter id missing")
    print(f"[test] admin sees HEALTH_KEYWORD needs_new_filter id={kid}")
    if auto_resolve:
        admin_resolve(opener, base, kid)
        require(
            kid not in {int(n["id"]) for n in admin_unresolved(opener, base)},
            f"resolved needs_new_filter id={kid} still in unresolved list",
        )
        print("[test] resolve needs_new_filter — gone from /unresolved")
    else:
        print(
            f"[test] Resolve needs_new_filter in browser: {base}/admin/notifications (id={kid}) "
            "or use --auto-resolve-notifications"
        )


def run_device_api_self_tests(base: str) -> None:
    """Register one device; verify steady rate limit, bulk ingest, CO/health payloads, idempotency."""
    ping(base)
    register_url = f"{base.rstrip('/')}/api/v1/devices/register"
    readings_url = f"{base.rstrip('/')}/api/v1/devices/readings"
    serial = f"PYSELFTEST-{uuid.uuid4().hex[:10]}"
    code, text = post_json(register_url, {"serialNumber": serial, "firmwareVersion": "test-1.0"})
    require(code == 201, f"register: expected HTTP 201, got {code}: {text[:400]}")
    reg = json.loads(text)
    require(bool(reg.get("apiToken")), "register: missing apiToken")
    require(reg.get("serialNumber") == serial, f"register: serial mismatch {reg.get('serialNumber')}")
    token = reg["apiToken"]
    auth = {"Authorization": f"Bearer {token}"}
    print("[test] register 201 + token")

    t0 = datetime.now(timezone.utc)

    def sample_row(offset_min: int, **overrides: Any) -> dict[str, Any]:
        at = t0 + timedelta(minutes=offset_min)
        row: dict[str, Any] = {
            "recordedAt": utc_iso(at),
            "temperatureCelsius": 22.0,
            "humidityPercent": 40.0,
            "carbonMonoxidePpm": 1.0,
            "healthStatus": "ok",
        }
        row.update(overrides)
        return row

    one = {"readings": [sample_row(0)]}
    c, t = post_json(readings_url, one, auth)
    require(c == 200, f"first single-sample: expected 200, got {c}: {t[:400]}")
    j = json.loads(t)
    require(j.get("acceptedSamples") == 1, f"first single: acceptedSamples want 1, got {j}")
    print("[test] first single-sample 200, acceptedSamples=1")

    c2, t2 = post_json(readings_url, one, auth)
    require(c2 == 429, f"second single immediate: expected 429, got {c2}: {t2[:400]}")
    print("[test] second single-sample 429 (steady throttle)")

    time.sleep(3)
    bulk2 = {"readings": [sample_row(120), sample_row(121)]}
    c3, t3 = post_json(readings_url, bulk2, auth)
    require(c3 in (200, 202), f"bulk(2): expected 200 or 202, got {c3}: {t3[:400]}")
    j3 = json.loads(t3)
    require(j3.get("acceptedSamples") == 2, f"bulk(2): acceptedSamples want 2, got {j3}")
    print("[test] bulk 2 samples 200/202, acceptedSamples=2")

    time.sleep(3)
    co_bulk = {
        "readings": [
            sample_row(240, carbonMonoxidePpm=10.5),
            sample_row(241, carbonMonoxidePpm=2.0),
        ]
    }
    c4, t4 = post_json(readings_url, co_bulk, auth)
    require(c4 in (200, 202), f"CO bulk: expected 200/202, got {c4}: {t4[:400]}")
    j4 = json.loads(t4)
    require(j4.get("acceptedSamples") == 2, f"CO bulk: acceptedSamples {j4}")
    print("[test] bulk with CO > 9 PPM accepted (use --admin-email to assert admin notification)")

    time.sleep(3)
    health_bulk = {
        "readings": [
            sample_row(360, healthStatus="needs_service"),
            sample_row(361, healthStatus="ok"),
        ]
    }
    c5, t5 = post_json(readings_url, health_bulk, auth)
    require(c5 in (200, 202), f"health bulk: expected 200/202, got {c5}: {t5[:400]}")
    print("[test] bulk with health needs_service accepted")

    time.sleep(3)
    body_idem = {"readings": [sample_row(480), sample_row(481)]}
    idem_key = f"py-idem-{uuid.uuid4().hex}"
    extra = {"Idempotency-Key": idem_key}
    a1, b1 = post_json(readings_url, body_idem, auth, extra_headers=extra)
    require(a1 in (200, 202), f"idempotency 1st: {a1} {b1[:300]}")
    a2, b2 = post_json(readings_url, body_idem, auth, extra_headers=extra)
    require(a2 == a1, f"idempotency replay: status {a2} != first {a1}")
    jb1, jb2 = json.loads(b1), json.loads(b2)
    require(
        jb1.get("acceptedSamples") == jb2.get("acceptedSamples"),
        f"idempotency body acceptedSamples {jb1} vs {jb2}",
    )
    print("[test] Idempotency-Key replay returns same status and acceptedSamples")


def burst_notifications_ready(
    opener: urllib.request.OpenerDirector, base: str, outcomes: List[Optional[ThreadOutcome]]
) -> bool:
    """True when each burst device has CO_THRESHOLD + three HEALTH_KEYWORD rows in /unresolved."""
    try:
        unres = admin_unresolved(opener, base)
    except Exception:
        return False
    need_health = ("gas_leak", "needs_new_filter", "needs_service")
    for o in outcomes:
        if not o or not o.token:
            continue
        serial = o.serial
        if not any(
            n.get("type") == "CO_THRESHOLD" and serial in str(n.get("message") or "")
            for n in unres
        ):
            return False
        hks = {
            str(n.get("healthKeyword") or "").lower()
            for n in unres
            if n.get("type") == "HEALTH_KEYWORD" and serial in str(n.get("message") or "")
        }
        for kw in need_health:
            if kw not in hks:
                return False
    return True


def run_test_burst_smoke(
    base: str,
    thread_count: int = 8,
    bulk_samples: int = 4,
    admin_email: str = "",
    admin_password: str = "",
) -> None:
    """
    Synchronized bulk burst: each thread registers and posts one bulk.
    Bulk includes CO > 9 PPM + gas_leak / needs_new_filter / needs_service rows.
    With admin credentials, polls /api/admin/notifications until those appear for every device serial.
    """
    ping(base)
    n = max(2, min(thread_count, 40))
    bs = max(4, min(bulk_samples, 40))
    barrier = threading.Barrier(n)
    outcomes: List[Optional[ThreadOutcome]] = [None] * n
    lock = threading.Lock()
    workers: List[threading.Thread] = []
    for i in range(n):
        t = threading.Thread(
            target=barrier_worker,
            args=(i, base, bs, barrier, outcomes, lock, True),
            name=f"test-burst-{i}",
            daemon=True,
        )
        workers.append(t)
        t.start()
    for t in workers:
        t.join(timeout=120)
    reg_ok = sum(1 for o in outcomes if o and o.register_http == 201)
    require(reg_ok == n, f"burst smoke: want {n}× register 201, got {reg_ok}")
    bad_bulk = [
        o.worker_id
        for o in outcomes
        if o and o.token and o.bulk_http is not None and o.bulk_http not in (200, 202)
    ]
    require(not bad_bulk, f"burst smoke: bad bulk HTTP for workers {bad_bulk}")
    ok_bulk = sum(1 for o in outcomes if o and o.bulk_http in (200, 202))
    require(ok_bulk == n, f"burst smoke: want {n}× bulk 200/202, got {ok_bulk}")
    print(
        f"[test] barrier burst: {n} devices × {bs} samples (CO>9 PPM + 3 health keywords per bulk) — HTTP OK"
    )

    if admin_email and admin_password:
        op = admin_login_opener(base, admin_email, admin_password)
        poll_until(
            60.0,
            0.5,
            "admin notifications after burst (async workers may delay DB)",
            lambda: burst_notifications_ready(op, base, outcomes),
        )
        print(
            "[test-burst] admin /unresolved shows CO_THRESHOLD + HEALTH (gas_leak, needs_new_filter, needs_service) per device serial"
        )
    else:
        print(
            "[test-burst] skip notification verification — pass --admin-email / --admin-password (same as full admin tests)"
        )


# --- optional one-shot barrier load test ---


@dataclass
class ThreadOutcome:
    worker_id: int
    serial: str
    register_http: int
    register_error: str
    bulk_http: Optional[int] = None
    bulk_body_snip: str = ""
    token: Optional[str] = None


def barrier_worker(
    worker_id: int,
    base_url: str,
    bulk_samples: int,
    barrier: threading.Barrier,
    outcomes: List[Optional[ThreadOutcome]],
    outcomes_lock: threading.Lock,
    alert_bulk: bool = False,
) -> None:
    register_url = f"{base_url.rstrip('/')}/api/v1/devices/register"
    readings_url = f"{base_url.rstrip('/')}/api/v1/devices/readings"
    serial = f"PYSIM-{worker_id}-{uuid.uuid4().hex[:8]}"
    reg_body = {"serialNumber": serial, "firmwareVersion": f"py-load-{worker_id}.0.0"}

    outcome = ThreadOutcome(
        worker_id=worker_id,
        serial=serial,
        register_http=-1,
        register_error="",
        token=None,
    )
    try:
        code, text = post_json(register_url, reg_body)
        outcome.register_http = code
        if code != 201:
            outcome.register_error = text[:500]
        else:
            reg = json.loads(text)
            outcome.token = reg["apiToken"]
    except Exception as e:
        outcome.register_http = -1
        outcome.register_error = str(e)[:500]

    barrier.wait()

    if outcome.token:
        auth = {"Authorization": f"Bearer {outcome.token}"}
        bulk = (
            build_bulk_burst_with_alerts(bulk_samples)
            if alert_bulk
            else build_bulk_fixed_samples(bulk_samples)
        )
        c, t = post_json(readings_url, bulk, auth)
        outcome.bulk_http = c
        outcome.bulk_body_snip = t[:400]
    else:
        outcome.bulk_http = None
        outcome.bulk_body_snip = "(skipped — registration failed)"

    with outcomes_lock:
        outcomes[worker_id] = outcome


def run_burst_once(base: str, n: int, bulk_samples: int) -> None:
    barrier = threading.Barrier(n)
    outcomes: List[Optional[ThreadOutcome]] = [None] * n
    lock = threading.Lock()
    threads: List[threading.Thread] = []
    t0 = time.perf_counter()
    for i in range(n):
        t = threading.Thread(
            target=barrier_worker,
            args=(i, base, bulk_samples, barrier, outcomes, lock, False),
            name=f"device-burst-{i}",
            daemon=True,
        )
        threads.append(t)
        t.start()
    for t in threads:
        t.join(timeout=HTTP_TIMEOUT_SEC + 60)
    elapsed = time.perf_counter() - t0
    reg_ok = sum(1 for o in outcomes if o and o.register_http == 201)
    bulk_counts: Dict[Optional[int], int] = {}
    for o in outcomes:
        key = None if not o or o.bulk_http is None else o.bulk_http
        bulk_counts[key] = bulk_counts.get(key, 0) + 1
    print(f"\nBurst done in {elapsed:.2f}s — registration 201: {reg_ok}/{n}")
    print("Bulk HTTP histogram:", dict(sorted((k, v) for k, v in bulk_counts.items() if k is not None)))
    for o in outcomes:
        if o and o.register_http != 201:
            print(f"[{o.worker_id}] register HTTP {o.register_http}: {o.register_error[:200]}")
        if o and o.token and o.bulk_http not in (200, 202):
            print(f"[{o.worker_id}] bulk HTTP {o.bulk_http}: {o.bulk_body_snip[:200]}")


def main() -> None:
    ap = argparse.ArgumentParser(
        description="Simulate many AC devices: long-running outage + bulk catch-up, or one-shot barrier burst."
    )
    ap.add_argument("--base-url", default="http://127.0.0.1:8080", help="Smart AC base URL")
    ap.add_argument(
        "--internet-down-100",
        action="store_true",
        help="Preset: 100 device threads, outage+bulk only (--steady-before-outage 0). Overrides --threads.",
    )
    ap.add_argument("--threads", type=int, default=DEFAULT_THREADS, help="Number of device threads")
    ap.add_argument(
        "--burst-once",
        action="store_true",
        help="Instead of infinite loop: register all, Barrier, simultaneous bulk once, then exit.",
    )
    ap.add_argument(
        "--bulk-samples",
        type=int,
        default=10,
        help="With --burst-once: readings per bulk (>=2). Ignored for outage loop (backlog follows outage length).",
    )
    ap.add_argument(
        "--outage-min-sec",
        type=int,
        default=60,
        help="Minimum simulated internet-down duration per cycle (no HTTP)",
    )
    ap.add_argument(
        "--outage-max-sec",
        type=int,
        default=180,
        help="Maximum simulated internet-down duration per cycle (bulk rows ≈ ceil(outage_sec/60), e.g. 300→5)",
    )
    ap.add_argument(
        "--inject-sim-alert",
        "--sim-admin-notifications",
        action="store_true",
        dest="inject_sim_alert",
        help=(
            "Send readings that trigger the same admin rules as the web app: CO over 9 PPM plus "
            "health needs_service / needs_new_filter / gas_leak (rotating). Applied to the last row "
            "of each bulk and the last single-sample POST in each steady phase (if any)."
        ),
    )
    ap.add_argument(
        "--max-backlog-minutes",
        type=int,
        default=120,
        help="Cap on backlog minutes (one sample per minute in bulk after outage)",
    )
    ap.add_argument(
        "--steady-before-outage",
        type=int,
        default=DEFAULT_STEADY_BEFORE_OUTAGE,
        help="Single-sample /readings POSTs before each simulated outage (each spaced ~%ds; 0 = skip)"
        % STEADY_INTERVAL_SEC,
    )
    ap.add_argument(
        "--steady-after-bulk",
        type=int,
        default=0,
        help="Single-sample POSTs after each bulk catch-up (normal steady telemetry, ~%ds apart; 0 = skip)"
        % STEADY_INTERVAL_SEC,
    )
    ap.add_argument(
        "--inter-cycle-sec",
        type=int,
        default=INTER_CYCLE_SEC,
        help="Sleep after each bulk before next outage (respect server bulk spacing)",
    )
    ap.add_argument(
        "--demo-admin-notifications",
        action="store_true",
        help=(
            "Run only the notification spec: CO>9 PPM, needs_service, gas_leak, needs_new_filter, "
            "each verified in /api/admin/notifications/unresolved then resolved (requires admin login). "
            "Use --admin-email / --admin-password or SMARTAC_* env vars. Ignored when --test is also passed."
        ),
    )
    ap.add_argument(
        "--test",
        action="store_true",
        help="Run device API self-tests against --base-url, print results, exit 0/1",
    )
    ap.add_argument(
        "--test-burst",
        action="store_true",
        help="With --test: barrier burst (8×4) with CO>9 + health rows; with admin creds, asserts /api/admin/notifications",
    )
    ap.add_argument(
        "--admin-email",
        default="",
        help="Admin email for --test or --demo-admin-notifications (or SMARTAC_ADMIN_EMAIL)",
    )
    ap.add_argument(
        "--admin-password",
        default="",
        help="Admin password for --test or --demo-admin-notifications (or SMARTAC_ADMIN_PASSWORD)",
    )
    ap.add_argument(
        "--auto-resolve-notifications",
        action="store_true",
        help=(
            "With --test / --demo-admin-notifications: after each alert appears, POST resolve via API "
            "and assert it leaves /unresolved. Default is manual resolve only in the web UI."
        ),
    )
    args = ap.parse_args()
    if args.internet_down_100:
        args.threads = 100
        args.steady_before_outage = 0
    base = args.base_url.rstrip("/")
    n = max(1, args.threads)

    admin_email = (args.admin_email or os.environ.get("SMARTAC_ADMIN_EMAIL") or "").strip()
    admin_password = args.admin_password or os.environ.get("SMARTAC_ADMIN_PASSWORD") or ""

    if args.demo_admin_notifications and not args.test:
        if not admin_email or not admin_password:
            print(
                "--demo-admin-notifications requires --admin-email and --admin-password "
                "(or SMARTAC_ADMIN_EMAIL / SMARTAC_ADMIN_PASSWORD)",
                file=sys.stderr,
            )
            raise SystemExit(2)
        try:
            ping(base)
            run_admin_notification_tests(
                base, admin_email, admin_password, auto_resolve=args.auto_resolve_notifications
            )
        except TestError as e:
            print(f"NOTIFICATION DEMO FAIL: {e}", file=sys.stderr)
            raise SystemExit(1)
        except Exception as e:
            print(f"NOTIFICATION DEMO ERROR: {e}", file=sys.stderr)
            raise SystemExit(1)
        if args.auto_resolve_notifications:
            tail = "each resolved via API and removed from /unresolved"
        else:
            tail = "alerts visible — resolve each in /admin/notifications (UI), or re-run with --auto-resolve-notifications"
        print(f"NOTIFICATION SPEC DEMO OK — CO>9; needs_service; gas_leak; needs_new_filter; {tail}")
        return

    if args.test:
        try:
            run_device_api_self_tests(base)
            if admin_email and admin_password:
                run_admin_notification_tests(
                    base, admin_email, admin_password, auto_resolve=args.auto_resolve_notifications
                )
            else:
                print(
                    "[test] skip admin notification checks (CO/health + resolve via /api/admin/...) — "
                    "set --admin-email / --admin-password or SMARTAC_ADMIN_EMAIL / SMARTAC_ADMIN_PASSWORD"
                )
            if args.test_burst:
                run_test_burst_smoke(base, admin_email=admin_email, admin_password=admin_password)
        except TestError as e:
            print(f"SELF-TEST FAIL: {e}", file=sys.stderr)
            raise SystemExit(1)
        except Exception as e:
            print(f"SELF-TEST ERROR: {e}", file=sys.stderr)
            raise SystemExit(1)
        print("SELF-TEST OK — all checks passed")
        return

    try:
        ping(base)
    except Exception as e:
        print(f"Server not reachable: {e}")
        raise SystemExit(1)

    if args.burst_once:
        bulk_samples = max(2, args.bulk_samples)
        print(f"One-shot barrier burst: {n} devices, {bulk_samples} samples per bulk — base {base}")
        run_burst_once(base, n, bulk_samples)
        return

    steady_parts: List[str] = []
    if args.steady_before_outage:
        steady_parts.append(f"{args.steady_before_outage} single-sample(s) before outage")
    if args.steady_after_bulk:
        steady_parts.append(f"{args.steady_after_bulk} single-sample(s) after bulk")
    steady_desc = "; ".join(steady_parts) if steady_parts else "no single-sample steady phase (bulk-only)"
    print(f"Smart AC outage simulator: {n} threads — outage + bulk; {steady_desc} (Ctrl+C to stop) — base {base}")
    print(
        f"Outage each cycle: {args.outage_min_sec}-{args.outage_max_sec}s down, "
        f"then bulk catch-up (cap {args.max_backlog_minutes} min of samples; rows = ceil(outage_s/60))"
    )
    if args.inject_sim_alert:
        print(
            "—inject-sim-alert / —sim-admin-notifications: CO>9 + critical health on last bulk row "
            "and last steady single-sample (when steady phases run)"
        )
    else:
        print(
            "Tip: default payloads stay under CO 9 and health ok — no admin alerts; use "
            "--inject-sim-alert (alias --sim-admin-notifications) to match server notification rules"
        )
    stop = threading.Event()
    threads: List[threading.Thread] = []
    for i in range(n):
        t = threading.Thread(
            target=outage_worker,
            args=(
                i,
                base,
                stop,
                args.outage_min_sec,
                args.outage_max_sec,
                args.max_backlog_minutes,
                args.steady_before_outage,
                args.steady_after_bulk,
                max(3, args.inter_cycle_sec),
                args.inject_sim_alert,
            ),
            name=f"device-outage-{i}",
            daemon=True,
        )
        threads.append(t)
        t.start()

    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        print("\nStopping…")
        stop.set()
        for t in threads:
            t.join(timeout=5)


if __name__ == "__main__":
    main()
