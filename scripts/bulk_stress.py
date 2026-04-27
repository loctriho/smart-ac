import argparse
import concurrent.futures
import json
import math
import random
import statistics
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Optional


@dataclass(frozen=True)
class Result:
    status: Optional[int]
    latency_ms: float
    error: Optional[str] = None


def _urljoin(base: str, path: str) -> str:
    base = base.rstrip("/")
    return f"{base}{path}"


def _iso_z(dt: datetime) -> str:
    # Ensure Z format and seconds precision (no micros)
    return dt.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def build_bulk_body(device_index: int, bulk_size: int, *, inject_alerts: bool) -> bytes:
    # Use 2025 data (requested). Spread timestamps across devices but keep in-range for MySQL.
    anchor = datetime(2025, 1, 1, 0, 0, 0, tzinfo=timezone.utc) + timedelta(days=device_index * 2)
    readings = []

    # Make charts look "alive": smooth waves + small noise, device-specific phase.
    # Keep values in realistic bounds for nicer charts.
    phase = (device_index % 97) * 0.19
    temp_base = 20.0 + (device_index % 7) * 0.4
    hum_base = 47.0 + (device_index % 9) * 0.6
    rng = random.Random(10_000_019 + device_index)  # deterministic per device

    for i in range(bulk_size):
        t = anchor + timedelta(seconds=i * 60)

        # Temperature: daily-ish + shorter oscillation
        temp = (
            temp_base
            + 2.2 * math.sin(phase + (i / 180.0) * 2.0 * math.pi)
            + 0.6 * math.sin(phase * 0.7 + (i / 35.0) * 2.0 * math.pi)
            + rng.uniform(-0.25, 0.25)
        )
        temp = max(16.0, min(30.0, temp))

        # Humidity: slower drift + gentle wave, anti-correlated a bit with temp
        hum = (
            hum_base
            + 6.0 * math.sin(phase * 1.15 + (i / 240.0) * 2.0 * math.pi)
            - 0.35 * (temp - 21.0)
            + rng.uniform(-0.7, 0.7)
        )
        hum = max(20.0, min(75.0, hum))

        # Alerts:
        # - CO > 9 PPM triggers CO_THRESHOLD
        # - healthStatus in {gas_leak, needs_service, needs_new_filter} triggers HEALTH_KEYWORD
        if inject_alerts and i in (0, 1, 2, 3):
            if i == 0:
                co_ppm, health = 10.5, "ok"
            elif i == 1:
                co_ppm, health = 1.0, "gas_leak"
            elif i == 2:
                co_ppm, health = 1.0, "needs_service"
            else:
                co_ppm, health = 1.0, "needs_new_filter"
        elif inject_alerts:
            # Keep a small chance of extra CO spikes/health keywords so the dataset has variety.
            spike = rng.random() < 0.015
            co_ppm = (10.2 + rng.random() * 6.0) if spike else (0.6 + rng.random() * 1.6)
            if spike and rng.random() < 0.65:
                health = rng.choice(["needs_service", "needs_new_filter", "gas_leak"])
            elif rng.random() < 0.003:
                health = rng.choice(["needs_service", "needs_new_filter"])
            else:
                health = "ok"
        else:
            co_ppm = 0.6 + rng.random() * 1.6
            health = "ok"

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


def http_post(url: str, body: bytes, headers: dict[str, str], timeout_s: float) -> tuple[int, bytes]:
    req = urllib.request.Request(url, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout_s) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        # HTTPError is also a response (e.g., 503)
        return e.code, e.read() if e.fp else b""


def register_device(base_url: str, serial: str, timeout_s: float) -> str:
    url = _urljoin(base_url, "/api/v1/devices/register")
    body = json.dumps({"serialNumber": serial, "firmwareVersion": "py-stress"}).encode("utf-8")
    status, data = http_post(url, body, {"Content-Type": "application/json"}, timeout_s)
    if status != 201:
        raise RuntimeError(f"register failed status={status} body={data[:200]!r}")
    token = json.loads(data.decode("utf-8"))["apiToken"]
    return token


def post_bulk_readings(base_url: str, token: str, body: bytes, timeout_s: float) -> Result:
    url = _urljoin(base_url, "/api/v1/devices/readings")
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {token}",
    }
    t0 = time.perf_counter()
    try:
        status, _ = http_post(url, body, headers, timeout_s)
        ms = (time.perf_counter() - t0) * 1000.0
        return Result(status=status, latency_ms=ms)
    except Exception as e:  # network errors, timeouts, etc.
        ms = (time.perf_counter() - t0) * 1000.0
        return Result(status=None, latency_ms=ms, error=type(e).__name__)


def percentile(sorted_vals: list[float], p: float) -> float:
    if not sorted_vals:
        return float("nan")
    if p <= 0:
        return sorted_vals[0]
    if p >= 100:
        return sorted_vals[-1]
    k = (len(sorted_vals) - 1) * (p / 100.0)
    f = int(k)
    c = min(f + 1, len(sorted_vals) - 1)
    if f == c:
        return sorted_vals[f]
    d0 = sorted_vals[f] * (c - k)
    d1 = sorted_vals[c] * (k - f)
    return d0 + d1


def main() -> int:
    ap = argparse.ArgumentParser(description="Concurrent bulk readings stress (202 vs 503 + latency)")
    ap.add_argument("--base-url", required=True, help="e.g. http://127.0.0.1:8080")
    ap.add_argument("--concurrency", type=int, default=500, help="number of concurrent POSTs (default: 500)")
    ap.add_argument("--bulk-size", type=int, default=500, help="readings per request (default: 500)")
    ap.add_argument(
        "--inject-alerts",
        action="store_true",
        help="Inject abnormal CO/health rows so admin notifications are created (recommended).",
    )
    ap.add_argument("--timeout-s", type=float, default=30.0, help="per request timeout seconds")
    ap.add_argument("--register-timeout-s", type=float, default=15.0, help="per registration timeout seconds")
    ap.add_argument("--max-workers", type=int, default=500, help="threadpool size for client")
    args = ap.parse_args()

    if args.concurrency < 1:
        raise SystemExit("--concurrency must be >= 1")
    if args.bulk_size < 1:
        raise SystemExit("--bulk-size must be >= 1")

    conc = args.concurrency
    print(f"Registering {conc} devices...", flush=True)
    tokens: list[str] = []
    start = time.perf_counter()
    for i in range(conc):
        serial = f"PY-STRESS-{i}-{int(time.time()*1000)}"
        tokens.append(register_device(args.base_url, serial, args.register_timeout_s))
    reg_ms = (time.perf_counter() - start) * 1000.0

    print(f"Building request bodies...", flush=True)
    inject_alerts = bool(args.inject_alerts)
    if inject_alerts and args.bulk_size < 4:
        raise SystemExit("--bulk-size must be >= 4 when --inject-alerts is enabled")
    bodies = [build_bulk_body(i, args.bulk_size, inject_alerts=inject_alerts) for i in range(conc)]

    print(f"Sending {conc} concurrent bulk POSTs...", flush=True)
    start = time.perf_counter()
    results: list[Result] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=min(args.max_workers, conc)) as ex:
        futs = [
            ex.submit(post_bulk_readings, args.base_url, tokens[i], bodies[i], args.timeout_s)
            for i in range(conc)
        ]
        for f in concurrent.futures.as_completed(futs):
            results.append(f.result())
    wall_ms = (time.perf_counter() - start) * 1000.0

    # Summaries
    count_202 = sum(1 for r in results if r.status == 202)
    count_503 = sum(1 for r in results if r.status == 503)
    count_other = sum(1 for r in results if (r.status is not None and r.status not in (202, 503)))
    count_err = sum(1 for r in results if r.status is None)

    lat = sorted(r.latency_ms for r in results)
    avg = statistics.fmean(lat) if lat else float("nan")

    print()
    print("BULK_PY_STRESS")
    print(f"  base_url={args.base_url}")
    print(f"  concurrency={conc} bulk_size={args.bulk_size}")
    print(f"  register_wall_ms={reg_ms:.1f}")
    print(f"  submit_wall_ms={wall_ms:.1f}")
    print(f"  status_202={count_202} status_503={count_503} other_status={count_other} client_errors={count_err}")
    print(
        "  latency_ms:"
        f" min={lat[0]:.1f} p50={percentile(lat,50):.1f} p95={percentile(lat,95):.1f} "
        f"p99={percentile(lat,99):.1f} max={lat[-1]:.1f} avg={avg:.1f}"
    )

    if count_err:
        # Print a small breakdown of client errors
        by_err: dict[str, int] = {}
        for r in results:
            if r.error:
                by_err[r.error] = by_err.get(r.error, 0) + 1
        top = sorted(by_err.items(), key=lambda kv: kv[1], reverse=True)[:5]
        print("  top_client_errors=" + ", ".join(f"{k}:{v}" for k, v in top))

    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)

