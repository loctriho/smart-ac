# Scripts

This folder contains Python helpers for interacting with a running Smart AC server.

## Layout

- `scripts/integration/`: smoke tests that mirror Java integration tests (expected status codes, API semantics).
- `scripts/load/`: load / stress tools (currently: “waves until 503”).
- `scripts/utils/`: long-running simulators and misc helpers.
- `scripts/bulk_stress.py`: shared helpers (HTTP calls, payload builders) imported by several scripts.

## Quick examples

- Mirror the Java integration tests against a running server:

```bash
python scripts/integration/mirror_java_integration_tests.py --base-url http://127.0.0.1:8080
```

- Run “waves until first 503” (production-style bulk waves):

```bash
python scripts/load/prod_5k_bulk_until_503.py --base-url http://127.0.0.1:8080
```

- Steady cadence (each device POSTs once per tick, default tick every 1s):

```bash
python scripts/load/steady_tick_devices.py --base-url http://127.0.0.1:8080 --devices 50 --interval 1
```

## Script intents (what each file is for)

### `scripts/integration/`

- **`mirror_java_integration_tests.py`**: Smoke/parity checks against a running server (mirrors the key Java integration tests and validates expected status codes/behaviors).
- **`device_readings_unit_tests.py`**: Lightweight `unittest` suite that hits a running server (register, ingest 1 vs 500, rate limit behavior).

### `scripts/load/`

- **`prod_5k_bulk_until_503.py`**: “Production-style” test: register a fleet, then send synchronized bulk waves until the first `503` (queue saturation). Good for finding the sustainable wave budget and when backpressure kicks in.
- **`steady_tick_devices.py`**: Register N devices, then on a fixed interval (default 1s) send one single-sample readings POST per device per tick (steady load, not wave-until-503). See script docstring for interaction with the per-device 60s rate limit.

### `scripts/utils/`

- **`simulate_multi_devices.py`**: Long-running “N devices as N threads” simulator (outage + bulk catch-up loops), with optional admin-notification demo and self-test mode.

