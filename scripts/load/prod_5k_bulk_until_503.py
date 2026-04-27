#!/usr/bin/env python3
"""
Production-like fleet load test (until first HTTP 503).

Flow:
1) Register N devices (default 5000) in a short sync window.
2) Send bulk readings waves from active devices in each wave.
3) Keep waves running until first 503 (or max waves).

By default this is "near-simultaneous" instead of a perfect barrier burst:
- registrations are spread in a short window (--register-window-ms)
- readings are spread in a short arrival window (--arrival-window-ms)

Set those windows to 0 for strict all-at-once behavior.

Payloads include abnormal rows (CO > 9 and abnormal health keywords) to trigger
notifications, while most rows remain normal so the mix is closer to production.
"""

from __future__ import annotations

import argparse
import json
import random
import statistics
import threading
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone

import os
import sys

# Allow running this script directly from subfolders (keep bulk_stress.py in scripts/).
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from bulk_stress import Result, percentile, post_bulk_readings, register_device


def _status_counter(results: list[Result]) -> Counter:
    c: Counter = Counter()
    for r in results:
        if r.status is None or r.error:
            c["client_err"] += 1
        else:
            c[r.status] += 1
    return c


def _iso_z(dt: datetime) -> str:
    return dt.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _build_prod_bulk_body(device_index: int, bulk_size: int, wave: int) -> bytes:
    """
    Build a production-like payload with recent timestamps.
    Includes forced abnormal rows in first 4 samples to trigger notifications.
    """
    rng = random.Random(3_000_017 + (wave * 100_003) + device_index)
    base = datetime.now(timezone.utc) - timedelta(minutes=bulk_size + rng.randint(0, 5))
    readings = []
    abnormal_health = ("gas_leak", "needs_service", "needs_new_filter")

    for i in range(bulk_size):
        t = base + timedelta(minutes=i)
        temp = max(16.0, min(31.0, 22.5 + rng.uniform(-3.2, 3.2)))
        hum = max(18.0, min(80.0, 48.0 + rng.uniform(-12.0, 12.0)))

        if i == 0:
            co_ppm = round(10.2 + rng.uniform(0.0, 3.8), 2)  # guaranteed CO alert
            health = "ok"
        elif i in (1, 2, 3):
            co_ppm = round(0.7 + rng.uniform(0.0, 1.0), 2)
            health = abnormal_health[i - 1]  # guaranteed health alert
        else:
            if rng.random() < 0.02:
                co_ppm = round(9.5 + rng.uniform(0.0, 7.0), 2)
            else:
                co_ppm = round(0.6 + rng.uniform(0.0, 2.2), 2)
            health = rng.choice(abnormal_health) if rng.random() < 0.01 else "ok"

        readings.append(
            {
                "recordedAt": _iso_z(t),
                "temperatureCelsius": round(temp, 2),
                "humidityPercent": round(hum, 2),
                "carbonMonoxidePpm": co_ppm,
                "healthStatus": health,
            }
        )
    return json.dumps({"readings": readings}).encode("utf-8")


def _register_all_at_once(
    base_url: str,
    devices: int,
    timeout_s: float,
    serial_prefix: str,
    register_window_ms: int,
) -> list[str]:
    barrier = threading.Barrier(devices) if register_window_ms <= 0 else None
    tokens: list[str | None] = [None] * devices
    failures: list[str] = []
    lock = threading.Lock()

    def worker(i: int) -> None:
        serial = f"{serial_prefix}-{i:05d}-{time.time_ns()}"
        try:
            if barrier is not None:
                barrier.wait()
            else:
                # Spread registration start over a short window to mimic real fleets.
                time.sleep((register_window_ms / 1000.0) * random.random())
            tokens[i] = register_device(base_url, serial, timeout_s)
        except Exception as e:  # noqa: BLE001
            with lock:
                failures.append(f"{i}:{type(e).__name__}:{e}")

    t0 = time.perf_counter()
    with ThreadPoolExecutor(max_workers=devices) as ex:
        futs = [ex.submit(worker, i) for i in range(devices)]
        for f in futs:
            f.result()
    wall_s = time.perf_counter() - t0

    ok_tokens = [t for t in tokens if t is not None]
    print(
        f"Registration wave done: total={devices} ok={len(ok_tokens)} failed={len(failures)} wall_s={wall_s:.2f}",
        flush=True,
    )
    if failures:
        print(f"First registration error: {failures[0]}", flush=True)
    if len(ok_tokens) != devices:
        raise SystemExit("Registration did not complete for all devices; aborting.")
    return ok_tokens


def _pick_active_indices(total_devices: int, active_ratio: float, wave: int) -> list[int]:
    n_active = max(1, min(total_devices, int(round(total_devices * active_ratio))))
    if n_active >= total_devices:
        return list(range(total_devices))
    rng = random.Random(5_000_033 + wave)
    return sorted(rng.sample(range(total_devices), n_active))


def _bulk_wave(
    base_url: str,
    tokens: list[str],
    active_indices: list[int],
    bulk_size: int,
    timeout_s: float,
    wave: int,
    arrival_window_ms: int,
) -> tuple[list[Result], float]:
    n = len(active_indices)
    barrier = threading.Barrier(n) if arrival_window_ms <= 0 else None
    results: list[Result | None] = [None] * n

    def worker(slot: int) -> None:
        i = active_indices[slot]
        body = _build_prod_bulk_body(i, bulk_size, wave)
        if barrier is not None:
            barrier.wait()
        else:
            # Spread request starts over a short window to emulate real network jitter.
            time.sleep((arrival_window_ms / 1000.0) * random.random())
        results[slot] = post_bulk_readings(base_url, tokens[i], body, timeout_s)

    t0 = time.perf_counter()
    with ThreadPoolExecutor(max_workers=n) as ex:
        futs = [ex.submit(worker, slot) for slot in range(n)]
        for f in futs:
            f.result()
    wall_s = time.perf_counter() - t0
    out = [r for r in results if r is not None]
    return out, wall_s


def _print_wave_line(wave: int, wall_s: float, active: int, total: int, results: list[Result]) -> None:
    c = _status_counter(results)
    lat = sorted(r.latency_ms for r in results if r.latency_ms is not None and r.status is not None and not r.error)
    p50 = percentile(lat, 50.0) if lat else float("nan")
    p95 = percentile(lat, 95.0) if lat else float("nan")
    print(
        f"wave={wave:03d} wall_s={wall_s:7.2f} "
        f"active={active:5d}/{total:5d} "
        f"202={c.get(202, 0):5d} 503={c.get(503, 0):5d} 429={c.get(429, 0):5d} "
        f"other={sum(v for k, v in c.items() if k not in (202, 503, 429, 'client_err')):5d} "
        f"client_err={c.get('client_err', 0):5d} "
        f"p50_ms={p50:8.1f} p95_ms={p95:8.1f}",
        flush=True,
    )


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--base-url", required=True, help="e.g. http://127.0.0.1:8080")
    ap.add_argument("--devices", type=int, default=5000, help="Number of devices for registration and each wave")
    ap.add_argument("--bulk-size", type=int, default=500, help="Readings per request (server max is usually 500)")
    ap.add_argument("--register-timeout-s", type=float, default=60.0, help="Per-registration timeout")
    ap.add_argument("--post-timeout-s", type=float, default=180.0, help="Per-bulk-request timeout")
    ap.add_argument("--cooldown-s", type=float, default=2.0, help="Pause between waves")
    ap.add_argument("--max-waves", type=int, default=200, help="Safety cap if no 503 appears")
    ap.add_argument("--serial-prefix", default="PROD-5K-503", help="Serial prefix for registrations")
    ap.add_argument(
        "--active-ratio",
        type=float,
        default=1.0,
        help="Fraction of fleet active each wave (0.0..1.0), default 1.0",
    )
    ap.add_argument(
        "--register-window-ms",
        type=int,
        default=1500,
        help="Spread device registration starts across this window (0 = strict all-at-once)",
    )
    ap.add_argument(
        "--arrival-window-ms",
        type=int,
        default=1200,
        help="Spread readings start times in each wave (0 = strict all-at-once)",
    )
    args = ap.parse_args()

    if args.devices < 1:
        raise SystemExit("--devices must be >= 1")
    if args.bulk_size < 4:
        raise SystemExit("--bulk-size must be >= 4 (alert rows are injected in first 4 samples)")
    if args.bulk_size > 500:
        raise SystemExit("--bulk-size must be <= 500")
    if not (0 < args.active_ratio <= 1):
        raise SystemExit("--active-ratio must be > 0 and <= 1")
    if args.max_waves < 1:
        raise SystemExit("--max-waves must be >= 1")
    if args.cooldown_s < 0:
        raise SystemExit("--cooldown-s must be >= 0")
    if args.register_window_ms < 0:
        raise SystemExit("--register-window-ms must be >= 0")
    if args.arrival_window_ms < 0:
        raise SystemExit("--arrival-window-ms must be >= 0")

    base = args.base_url.rstrip("/")

    print(
        f"Starting production-style test: devices={args.devices} bulk_size={args.bulk_size} "
        f"active_ratio={args.active_ratio:.2f} max_waves={args.max_waves} cooldown_s={args.cooldown_s} "
        f"register_window_ms={args.register_window_ms} arrival_window_ms={args.arrival_window_ms}",
        flush=True,
    )
    print("Step 1/2: registering all devices at once...", flush=True)
    tokens = _register_all_at_once(
        base,
        args.devices,
        args.register_timeout_s,
        args.serial_prefix,
        args.register_window_ms,
    )

    print("Step 2/2: sending synchronized bulk waves until first 503...", flush=True)
    t_start = time.perf_counter()
    first_503_wave: int | None = None
    first_503_elapsed_s: float | None = None
    agg = Counter()
    wave_walls: list[float] = []
    wave_requests = 0

    for wave in range(1, args.max_waves + 1):
        active_indices = _pick_active_indices(args.devices, args.active_ratio, wave)
        results, wall_s = _bulk_wave(
            base,
            tokens,
            active_indices,
            args.bulk_size,
            args.post_timeout_s,
            wave,
            args.arrival_window_ms,
        )
        wave_walls.append(wall_s)
        wave_requests += len(results)
        c = _status_counter(results)
        agg.update(c)
        _print_wave_line(wave, wall_s, len(active_indices), args.devices, results)

        if c.get(503, 0) > 0:
            first_503_wave = wave
            first_503_elapsed_s = time.perf_counter() - t_start
            break

        if args.cooldown_s > 0:
            time.sleep(args.cooldown_s)

    total_elapsed_s = time.perf_counter() - t_start

    print("\n=== SUMMARY ===", flush=True)
    print(
        f"devices={args.devices} active_ratio={args.active_ratio:.2f} "
        f"bulk_size={args.bulk_size} total_requests={wave_requests}",
        flush=True,
    )
    print(f"waves_sent={len(wave_walls)} total_elapsed_s={total_elapsed_s:.2f}", flush=True)
    if wave_walls:
        print(
            "wave_wall_s min={:.2f} p50={:.2f} p95={:.2f} max={:.2f}".format(
                min(wave_walls),
                statistics.median(wave_walls),
                percentile(sorted(wave_walls), 95.0),
                max(wave_walls),
            ),
            flush=True,
        )
    print(
        "aggregate_statuses: 202={} 503={} 429={} other={} client_err={}".format(
            agg.get(202, 0),
            agg.get(503, 0),
            agg.get(429, 0),
            sum(v for k, v in agg.items() if k not in (202, 503, 429, "client_err")),
            agg.get("client_err", 0),
        ),
        flush=True,
    )

    if first_503_wave is not None:
        print(f"FIRST_503 wave={first_503_wave} elapsed_s={first_503_elapsed_s:.2f}", flush=True)
        return 0

    print("No 503 observed before max-waves. Increase --max-waves or raise load.", flush=True)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
