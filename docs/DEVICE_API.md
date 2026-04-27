# Smart AC — Device HTTP API

Base URL: same origin as deployment (e.g. `https://your-host`).

All JSON bodies use `application/json`. Timestamps are ISO-8601 instants in UTC (e.g. `2026-04-21T12:00:00Z`).

See also:

- `docs/API_GUIDE.md` (how the APIs fit together)
- `docs/ARCHITECTURE.md` (design rationale)

---

## 1. Register a device

`POST /api/v1/devices/register`

**Authentication:** none.

**Body:**

```json
{
  "serialNumber": "AC-0001",
  "firmwareVersion": "1.4.2"
}
```

**Response:** `201 Created`

```json
{
  "deviceId": 1,
  "serialNumber": "AC-0001",
  "registrationDate": "2026-04-21T14:30:00Z",
  "firmwareVersion": "1.4.2",
  "apiToken": "sac_…"
}
```

The **`apiToken`** is shown **only once**. Store it on the device; the server keeps a hash.

**Errors:** `409` if the serial number is already registered.

---

## 2. Upload sensor readings (bulk)

`POST /api/v1/devices/readings`

**Authentication:** `Authorization: Bearer <apiToken>` (token from registration).

**Body:** up to **500** samples per request. Each sample must include all fields:

```json
{
  "readings": [
    {
      "recordedAt": "2026-04-21T12:00:00Z",
      "temperatureCelsius": 22.5,
      "humidityPercent": 48.0,
      "carbonMonoxidePpm": 3.0,
      "healthStatus": "ok"
    }
  ]
}
```

| Field | Meaning |
|--------|--------|
| `temperatureCelsius` | °C |
| `humidityPercent` | 0–100 |
| `carbonMonoxidePpm` | parts per million |
| `healthStatus` | Max 150 characters. Stored as text for operators; does **not** create admin notifications in this build (CO-only notifications). |

**Response:**

- **`200 OK`** — body persisted before the response is returned (single-sample uploads, or multi-sample when async ingest is turned off).
- **`202 Accepted`** — multi-sample payload accepted for **background persistence** when the server has async ingest enabled (`app.device-ingest.async-enabled=true`). Body shape:

```json
{ "acceptedSamples": 25, "queued": true }
```

Single-sample success omits `queued` or leaves it unset in JSON:

```json
{ "acceptedSamples": 1 }
```

**Optional header:** `Idempotency-Key` — opaque string (per logical upload). Repeating the same key within the server’s TTL returns the **same status and JSON** without duplicating rows (in-memory store; use a shared store if you run multiple app instances).

**Semantics:**

- **Catch-up / backlog:** send many samples in one request (up to 500). This is how devices should flush data after Wi‑Fi or server outages. With async ingest, the HTTP response returns quickly while a **bounded worker pool** batch-writes to the database.
- **Steady state:** sending **exactly one** sample per request is limited to **once per ~55 seconds** per device. If the device sends a single sample more often, the API returns **`429 Too Many Requests`** with a message explaining that bulk payloads are for catch-up.
- **Bulk spacing:** multi-sample requests are additionally limited to **once every few seconds** per device (default 2s, `app.device-ingest.min-bulk-interval-seconds`) to reduce accidental hammering.
- **Overload:** if the ingest queue is too deep or a **circuit breaker** opened after repeated DB errors, the API may return **`503 Service Unavailable`** with a short error message — devices should **back off** and retry.
- **Recorded time rules:** the server rejects readings in a **future UTC minute** and normalizes all `recordedAt` values to **UTC minute precision** on ingest.

**Errors:**

| Code | Situation |
|------|-----------|
| `401` | Missing/invalid bearer token |
| `403` | Device disabled |
| `400` | Validation error (empty list, >500 samples, future timestamp, etc.) |
| `429` | Single-sample upload too soon, or bulk uploads spaced too closely |
| `503` | Ingest queue saturated or persistence circuit open |

---

## 3. Operational notes

- **HTTPS** should be used in production; tokens are bearer secrets.
- **Server downtime:** devices should retry and **batch** readings into larger payloads until uploads succeed (as required by the product brief).
- **Admin panel** is separate (browser login); it is not part of the device API.
