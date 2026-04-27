# Smart AC — API Guide

This app has two API surfaces:

- **Device API** (`/api/v1/devices/**`): used by AC units to register and upload telemetry.
- **Admin API** (`/api/admin/**`): used by the admin web UI to list devices, draw charts, and manage alerts/admins.

If you only integrate devices, start with `docs/DEVICE_API.md`.

---

## Device API (what it achieves)

The device API is designed for **unreliable connectivity**:

- Devices can **batch readings** (catch-up/backlog) instead of sending every sample immediately.
- The server can accept large bulks quickly and **persist in the background** (HTTP `202`) using a bounded queue.
- Devices get explicit backpressure:
  - `429 Too Many Requests` when a device posts too frequently (per-device pacing).
  - `503 Service Unavailable` when the ingest queue is saturated (fleet-level overload).

### Authentication model (device)

Devices authenticate with `Authorization: Bearer <apiToken>`:

- The `apiToken` is returned only once during registration.
- The server stores **only a hash** (SHA-256) and looks up the device by that hash on every ingest call.
- There is no `deviceId` in the ingest URL/body; the **token identifies the device**.

### Timestamps (device telemetry)

Ingest applies two rules so dashboards behave predictably:

- **Future protection**: readings must not be in a **future UTC minute** versus server time.
- **Minute normalization**: `recordedAt` is normalized to **UTC minute precision** (sub-minute fields are discarded).

This prevents “future data” from breaking time-range queries and makes charts stable when devices have small clock skew.

---

## Admin API (what it achieves)

The admin API is a **private** JSON API intended for the built-in admin UI:

- List/search devices.
- Show device detail and recent readings.
- Fetch chart series for “today / week / month / year / all”.
- Show unresolved notifications (CO-threshold) and resolve them.
- Manage admins/invitations (admin-only operations).
- Server-Sent Events (SSE) stream used for lightweight “live” refresh hints.

### Authentication model (admin)

Admin endpoints are protected by **Spring Security session auth**:

- The browser logs in using the HTML login form (`/login`).
- After login, the UI calls `/api/admin/**` using the session cookie.

### Notifications (admin alerts)

Admin notifications are intentionally **CO-only**:

- If any sample in a persisted batch has **CO > 9 PPM**, the server creates **at most one** `CO_THRESHOLD` notification for that batch (using the max CO observed).
- Health keywords are stored on readings but **do not create notifications**.

This keeps the notification feed focused and avoids high-volume “health text” noise during bulk catch-up.

---

## Operational behaviors you’ll see under load

- **202 Accepted**: multi-sample request admitted and queued for background persistence.
- **429 Too Many Requests**: device posted again inside its per-device cooldown window.
- **503 Service Unavailable**: ingest executor queue is full (bounded backpressure); retry with exponential backoff + jitter.

---

## Trade-offs

- **No per-sample dedupe**: the DB does not enforce uniqueness on `(device_id, recorded_at)` for `sensor_readings`. If clients upload duplicate timestamps, charts can show repeated points. Use `Idempotency-Key` to dedupe retries at the request level.
- **Admin views aren’t fully live**: the admin UI favors on-demand fetch and occasional refresh over streaming high-volume readings to every open tab, to avoid extra server pressure during spikes.

