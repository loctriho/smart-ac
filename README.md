# Smart AC — Proof of Concept

Spring Boot 3 + Spring Data JPA + Spring Security: **device HTTP API** (bearer token) and **admin web app** (Thymeleaf + Chart.js) for managing AC units, alerts, and administrators.

## URLs (local)

| Surface | URL |
|--------|-----|
| Admin panel | http://localhost:8080/admin/ |
| Device API base | http://localhost:8080/api/v1/devices |

**Default admin (created on first startup if the database has no admins):**

- **Email:** `admin@smartac.local`  
- **Password:** `ChangeMe!Admin2024`  

Override via `app.bootstrap.email` / `app.bootstrap.password` in `application.properties`, or change after login.

## Database (MariaDB)

The app uses **MariaDB** at runtime via the **MariaDB JDBC driver** (`mariadb-java-client`). Defaults in `application.properties` (override with `MYSQL_*` env vars):

- **Host / port / database:** `localhost:3307` / `smartac` (URL option `createDatabaseIfNotExist=true` creates the schema if the account is allowed to).
- **User / password:** `root` / `root` (override with `MYSQL_USER`, `MYSQL_PASSWORD`, `MYSQL_HOST`, `MYSQL_PORT`, `MYSQL_DATABASE`).

Example: create a dedicated user (run as a MariaDB admin):

```sql
CREATE DATABASE IF NOT EXISTS smartac;
CREATE USER 'smartac'@'%' IDENTIFIED BY 'smartac';
GRANT ALL PRIVILEGES ON smartac.* TO 'smartac'@'%';
FLUSH PRIVILEGES;
```

`mvn test` uses **H2 in-memory** via `src/test/resources/application.properties` so CI does not need MariaDB.

## Run

Start MariaDB (or point `MYSQL_*` at your instance), then:

```bash
mvn spring-boot:run
```

Build & test:

```bash
mvn verify
```

## Device API documentation

See:

- [docs/API_GUIDE.md](docs/API_GUIDE.md) (overview: what each API does)
- [docs/DEVICE_API.md](docs/DEVICE_API.md) (device registration + bulk ingest)
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (architecture + rationale)

## Deploying (same domain)

Build a runnable JAR:

```bash
mvn -q package
java -jar target/smart-ac-poc-1.0.0-SNAPSHOT.jar
```

For a public URL, run behind HTTPS (reverse proxy or platform), set `app.base-url` to the public origin (for invitation and password-reset links), point `spring.datasource.*` at your managed MariaDB (or `MYSQL_*` env overrides), and add SMTP if you want password-reset emails (otherwise reset links are logged when `app.dev.log-password-reset-links=true`).

Docker:

```bash
docker build -t smart-ac-poc .
docker run -p 8080:8080 smart-ac-poc
```

## Requirements coverage & compromises

**Implemented:**

- Device registration (serial, registration time, firmware) and bearer token.
- Authenticated bulk ingest (≤500 samples), with steady-state single-sample rate limiting to ~1/minute, optional per-device bulk spacing, in-process queued multi-sample ingest (202 when enabled), and bulk for catch-up.
- Sensors: temperature (°C), humidity (%), CO (PPM), health text (≤150 chars).
- Admin login/logout; password reset flow (token in DB; email optional — link logged in dev).
- Invitation links for new admins; list/block/unblock admins (cannot self-block).
- List/search devices; device detail with graphs (temperature, humidity, CO) for today/week/month/year; health as text in table only.
- Notifications for CO > 9 PPM and for health keywords `needs_service`, `needs_new_filter`, `gas_leak`; resolve once for all admins; nav badge polling.

**Edge cases / pitfalls:**

- Time ranges for graphs use the **JVM default time zone**; production should fix a zone (e.g. UTC) per tenant.
- Production should use a managed MariaDB (or equivalent) with backups; `ddl-auto=update` is convenient for dev only.
- Registering `DeviceBearerAuthFilter` as a standalone `@Bean` would register it globally — the code instantiates it only inside the security chain (see `SecurityConfig`).
- CSRF is disabled for `/api/v1/devices/register` and `/api/admin/**` JSON calls; admin forms still use CSRF.

**Scaling (horizontal):** run **multiple instances** of the same JAR behind a load balancer with **sticky sessions disabled** for the device API (stateless bearer auth). Use a **managed MariaDB** tier; for read-heavy dashboards you can add **read replicas** later (routing `DataSource` or separate read URL — see commented `app.datasource.read.jdbc-url` in `application.properties`). Device bulk ingest can use **in-process bounded queues** and **202 Accepted** for multi-sample payloads when `app.device-ingest.async-enabled=true` (disabled in tests). For spikes beyond one JVM, replace the in-process queue with a **broker + consumer** that calls the same persistence logic.

**Possible later work:**

- WebSocket push for notifications instead of polling.
- Email sending for invitations and password reset (SMTP).
- PostgreSQL + Flyway, device token rotation, audit logs, stricter rate limits per device IP. For MariaDB production, prefer Flyway/Liquibase over relying on `ddl-auto=update`.

## License

Internal PoC — use as needed for the exercise submission (repo zip or GitHub link).
