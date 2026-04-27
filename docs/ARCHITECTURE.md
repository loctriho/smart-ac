# Smart AC — Architecture & Rationale

This document explains how the system is put together, and why the core design choices were made.

---

## High-level architecture

The app is a single Spring Boot service with two “front doors”:

- **Device ingest API** (`/api/v1/devices/**`): stateless bearer-token auth for devices.
- **Admin web + JSON API** (`/admin/` and `/api/admin/**`): session auth for humans.

Both share a single relational database (MariaDB in dev/prod; H2 in tests).

---

## Core goals and the design choices that support them

### Goal: tolerate outages and catch-up traffic

**What happens in the real world**

- Devices are offline sometimes (Wi‑Fi issues, server downtime, transient network failures).
- When they return, they may have minutes/hours of buffered readings.

**Design choices**

- The ingest endpoint accepts **bulk payloads** (up to a configured max).
- Large bulks can be admitted quickly and persisted **asynchronously**.

### Goal: protect the service under fleet spikes

**What can go wrong**

- Coordinated “waves” from a large fleet can overwhelm the JVM, DB pool, or DB itself.

**Design choices**

- A bounded ingest executor provides **backpressure**:
  - Queue full → `503` immediately (instead of OOM / runaway latency).
- A per-device rate limiter provides **fairness** and prevents accidental hammering by one device:
  - Too frequent → `429`.

### Goal: predictable charts and time ranges

**What can go wrong**

- Devices can send future timestamps due to clock drift.
- Sub-minute variability can create confusing chart behavior and edge cases at boundaries.

**Design choices**

- Reject readings in a **future UTC minute**.
- Normalize `recordedAt` to **UTC minute precision** on ingest.

### Goal: keep alerting actionable

**What can go wrong**

- Bulk uploads can generate huge volumes of “alerts” if every reading becomes a notification.

**Design choices**

- Alerts are **CO-only** and **at most one per batch**.
- Health status remains stored for operators, but it does not flood the notification feed.

---

## Ingest pipeline (end-to-end)

For `POST /api/v1/devices/readings`:

1. **Authenticate** device by bearer token (token hash lookup).
2. **Validate** payload size and fields.
3. **Normalize timestamps** to UTC minute.
4. **Reject future UTC-minute samples**.
5. **Enforce per-device cooldown** (`429` on violation).
6. For multi-sample payloads, **enqueue async persistence**:
   - If queue full → `503` with “retry with backoff”.
7. Persist readings in a **single transaction**, using chunked inserts and JDBC batching.
8. Update `Device.lastIngestAt`.
9. Create at most one **CO threshold** notification per batch, then notify admins (SSE hint).

---

## Deployment notes

- Use HTTPS in production: device tokens are bearer secrets.
- Do not rely on `ddl-auto=update` for production migrations (use Flyway/Liquibase).
- For horizontal scaling, replace in-process idempotency/queue with shared infrastructure (broker + shared idempotency store).

---

## Trade-offs (intentional compromises)

This PoC makes a few deliberate choices to keep the system simple and robust under stress. These are not “bugs”, but they do affect behavior.

### 1) No per-sample deduplication in the database

The ingest path **does not** enforce a uniqueness constraint like `(device_id, recorded_at)` on `sensor_readings`, and it does not attempt to dedupe individual samples during persistence.

Implications:

- If a device (or test tool) uploads the same minute multiple times, the DB can contain duplicates.
- Charts and “latest reading” views can look odd (extra points / multiple rows for the same time bucket).

What *is* supported:

- **Request-level idempotency** via `Idempotency-Key` (same key → same response without writing duplicates). This is a coarse, operational dedupe for retries, not a per-sample uniqueness guarantee.

### 2) Admin UI uses polling / manual refresh (not streaming device data)

The admin UI is optimized to avoid pushing large volumes of time-series data over a live stream:

- The UI loads charts and lists via on-demand JSON requests (and users may need to refresh to see newly persisted readings).
- SSE is used only for lightweight “something changed” hints, not for streaming all readings.

Implications:

- Under heavy ingest, the dashboard may not instantly reflect the newest readings unless you reload.
- This reduces server pressure versus streaming high-volume telemetry to every open admin browser tab.

### 3) Async ingest trades immediate consistency for admission under load

For multi-sample payloads, the server can respond `202 Accepted` and persist in the background.

Implications:

- A `202` means “queued”, not “already in the DB”.
- Immediately opening a device page after a wave can show “no samples yet” until the queue drains.

### 4) In-process queue and idempotency are not horizontally scalable

The bounded queue and idempotency cache are in-process for simplicity.

Implications:

- Running multiple app instances behind a load balancer requires a shared queue/idempotency store (or sticky routing) if you want retry dedupe and predictable backpressure across instances.


