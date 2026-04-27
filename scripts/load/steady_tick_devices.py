#!/usr/bin/env python3
"""
Steady tick load: register N devices, then every S seconds each device sends one
POST /api/v1/devices/readings with a single sample (sync 200 from server).

Unlike wave-based scripts, this keeps a fixed cadence (default: all devices fire
once per wall-clock tick).

IMPORTANT — server rules:
  - recordedAt must not be in a future UTC minute (otherwise HTTP 400). Payloads use
    timestamps a few minutes in the past so each tick stays valid.
  - Rate limit: one accepted readings POST per device per 60s by default. With
    --interval 1 expect 429 after the first accepted tick unless you set
    readings-rate-limit-seconds=0 on a test instance, or use --interval 60+.

Usage (from repo root):
  python scripts/load/steady_tick_devices.py --base-url http://127.0.0.1:8080 --devices 50

Stop with Ctrl+C.
"""

from __future__ import annotations

import argparse
import json
import os
import random
import signal
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from bulk_stress import Result, post_bulk_readings, register_device


def _iso_z(dt: datetime) -> str:
    return dt.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def build_single_reading_body(*, tick: int, device_index: int) -> bytes:
    """One sample; timestamps in the *past* so they pass server future-minute checks."""
    base = datetime.now(timezone.utc).replace(microsecond=0)
    # Server: recordedAt UTC minute must not be after current UTC minute (400 if "future").
    # Never add positive seconds to "now" — large tick*K + device_index becomes future time.
    age_sec = 120 + tick * 3 + device_index * 2
    t = base - timedelta(seconds=age_sec)
    rng = random.Random(900_001 + device_index * 1_003 + tick)
    temp = round(20.0 + rng.uniform(-2.0, 2.0), 2)
    hum = round(48.0 + rng.uniform(-5.0, 5.0), 2)
    co = round(0.6 + rng.uniform(0.0, 1.2), 2)
    readings = [
        {
            "recordedAt": _iso_z(t),
            "temperatureCelsius": temp,
            "humidityPercent": hum,
            "carbonMonoxidePpm": co,
            "healthStatus": "ok",
        }
    ]
    return json.dumps({"readings": readings}).encode("utf-8")


def _register_all(
    base: str,
    n: int,
    serial_prefix: str,
    register_timeout_s: float,
    reg_workers: int,
) -> list[str]:
    tokens: list[str | None] = [None] * n

    def reg_one(i: int) -> tuple[int, str]:
        serial = f"{serial_prefix}-{i:06d}-{int(time.time() * 1000)}"
        tok = register_device(base, serial, register_timeout_s)
        return i, tok

    print(f"Registering {n} devices (workers={reg_workers})...", flush=True)
    t0 = time.perf_counter()
    with ThreadPoolExecutor(max_workers=min(reg_workers, max(1, n))) as ex:
        futs = [ex.submit(reg_one, i) for i in range(n)]
        for f in as_completed(futs):
            i, tok = f.result()
            tokens[i] = tok
    wall = time.perf_counter() - t0
    print(f"Registration done in {wall:.2f}s", flush=True)
    return [t for t in tokens if t is not None]  # type: ignore[return-value]


def _tick(
    base: str,
    tokens: list[str],
    tick: int,
    post_timeout_s: float,
    post_workers: int,
) -> list[Result]:
    n = len(tokens)

    def post_one(i: int) -> Result:
        body = build_single_reading_body(tick=tick, device_index=i)
        return post_bulk_readings(base, tokens[i], body, post_timeout_s)

    out: list[Result] = []
    with ThreadPoolExecutor(max_workers=min(post_workers, max(1, n))) as ex:
        futs = [ex.submit(post_one, i) for i in range(n)]
        for f in as_completed(futs):
            out.append(f.result())
    return out


def _summarize(results: list[Result]) -> dict[str, int]:
    c: dict[str, int] = {}
    for r in results:
        if r.error:
            c["err"] = c.get("err", 0) + 1
        elif r.status is None:
            c["none"] = c.get("none", 0) + 1
        else:
            key = str(r.status)
            c[key] = c.get(key, 0) + 1
    return c


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--base-url", required=True, help="e.g. http://127.0.0.1:8080")
    ap.add_argument("--devices", type=int, default=10, help="Number of simulated devices")
    ap.add_argument(
        "--interval",
        type=float,
        default=1.0,
        help="Seconds between ticks (each tick: one POST per device)",
    )
    ap.add_argument(
        "--duration-s",
        type=float,
        default=0.0,
        help="Stop after this many seconds (0 = run until Ctrl+C)",
    )
    ap.add_argument(
        "--max-ticks",
        type=int,
        default=0,
        help="Stop after this many ticks (0 = unlimited with duration)",
    )
    ap.add_argument("--serial-prefix", default="TICK", help="Serial prefix for registration")
    ap.add_argument("--register-timeout-s", type=float, default=30.0)
    ap.add_argument("--post-timeout-s", type=float, default=30.0)
    ap.add_argument(
        "--register-workers",
        type=int,
        default=64,
        help="Thread pool size for parallel registration",
    )
    ap.add_argument(
        "--post-workers",
        type=int,
        default=256,
        help="Thread pool size per tick for parallel POSTs",
    )
    args = ap.parse_args()

    if args.devices < 1:
        raise SystemExit("--devices must be >= 1")
    if args.interval <= 0:
        raise SystemExit("--interval must be > 0")
    if args.duration_s < 0:
        raise SystemExit("--duration-s must be >= 0")
    if args.max_ticks < 0:
        raise SystemExit("--max-ticks must be >= 0")

    base = args.base_url.rstrip("/")
    stop = False

    def on_sigint(_sig, _frm) -> None:
        nonlocal stop
        stop = True

    signal.signal(signal.SIGINT, on_sigint)
    if hasattr(signal, "SIGTERM"):
        signal.signal(signal.SIGTERM, on_sigint)

    tokens = _register_all(
        base,
        args.devices,
        args.serial_prefix,
        args.register_timeout_s,
        args.register_workers,
    )
    if len(tokens) != args.devices:
        raise SystemExit("Registration incomplete")

    if args.interval < 60:
        print(
            "\nNote: with default server rate limit (60s per device), --interval < 60 usually "
            "yields 429 after the first successful tick. Use a test server with "
            "app.device-ingest.readings-rate-limit-seconds=0 or set --interval 60.\n",
            flush=True,
        )

    print(
        f"Starting ticks: devices={args.devices} interval_s={args.interval} "
        f"duration_s={args.duration_s or 'unlimited'} max_ticks={args.max_ticks or 'unlimited'}",
        flush=True,
    )

    t_run0 = time.perf_counter()
    tick = 0
    try:
        while not stop:
            tick += 1
            t0 = time.perf_counter()
            results = _tick(base, tokens, tick, args.post_timeout_s, args.post_workers)
            wall = time.perf_counter() - t0
            counts = _summarize(results)
            parts = [f"{k}={v}" for k, v in sorted(counts.items(), key=lambda x: x[0])]
            print(
                f"tick={tick:5d} wall_s={wall:7.2f} interval_s={args.interval}  " + " ".join(parts),
                flush=True,
            )

            if args.max_ticks and tick >= args.max_ticks:
                break
            if args.duration_s > 0 and (time.perf_counter() - t_run0) >= args.duration_s:
                break

            # Next tick starts ~interval seconds after this tick *started* (stable cadence).
            sleep_s = args.interval - (time.perf_counter() - t0)
            if sleep_s > 0:
                time.sleep(sleep_s)
    except KeyboardInterrupt:
        stop = True

    print("Stopped.", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
