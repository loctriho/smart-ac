# Installation guide — Smart AC PoC

This document describes how to install and run the application **from source** or **with Docker**. The runtime database is **MariaDB** (MariaDB JDBC driver; wire protocol is MySQL-compatible, hence the `MYSQL_*` environment variable names in Spring configuration).

---

## 1. Prerequisites

| Requirement | Notes |
|-------------|--------|
| **Java 21** | JDK for building; JRE 21 is enough to run a pre-built JAR. |
| **Maven 3.9+** | Required only if you build from source (`mvn package`). |
| **MariaDB** | Running and reachable before you start the app (10.x / 11.x recommended). |
| **Docker** (optional) | For containerized build and run. |

---

## 2. Database

The app connects using environment variables (or defaults in `src/main/resources/application.properties`).

**Default connection (when no env vars are set):**

| Setting | Default |
|---------|---------|
| Host | `localhost` |
| Port | `3307` |
| Database | `smartac` |
| User | `root` |
| Password | `root` |

Create the database and a user with sufficient privileges (example for MariaDB):

```sql
CREATE DATABASE IF NOT EXISTS smartac CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- Example: dedicated application user (adjust host and password)
CREATE USER IF NOT EXISTS 'smartac'@'%' IDENTIFIED BY 'your_secure_password';
GRANT ALL PRIVILEGES ON smartac.* TO 'smartac'@'%';
FLUSH PRIVILEGES;
```

If your server listens on **3306** instead of **3307**, set `MYSQL_PORT=3306` when starting the app (see below).

Hibernate `ddl-auto` is **update** in the bundled configuration (schema is created/updated automatically). For production, prefer a migration tool and a fixed schema strategy.

---

## 3. Install and run from source

### 3.1 Clone and build

```bash
cd /path/to/smart-ac-poc
mvn package
```

This produces `target/smart-ac-poc-1.0.0-SNAPSHOT.jar`.

To build without running tests:

```bash
mvn package -DskipTests
```

### 3.2 Configure (optional)

You can set **environment variables** before `mvn spring-boot:run` or `java -jar` (Spring Boot maps them to `application.properties` where configured):

| Variable | Purpose |
|----------|---------|
| `MYSQL_HOST` | Database host |
| `MYSQL_PORT` | Database port |
| `MYSQL_DATABASE` | Database name |
| `MYSQL_USER` | Database user |
| `MYSQL_PASSWORD` | Database password |

Optional JVM tuning (large deployments or heavy traffic):

```bash
export JAVA_OPTS="-Xmx2g -Xms256m"
```

### 3.3 Run (development)

With defaults on the same machine as the database:

```bash
mvn spring-boot:run
```

Or run the JAR:

```bash
java $JAVA_OPTS -jar target/smart-ac-poc-1.0.0-SNAPSHOT.jar
```

On **Windows (PowerShell)** with a non-default database password:

```powershell
$env:MYSQL_PASSWORD = "your_secure_password"
mvn spring-boot:run
```

### 3.4 Verify

- **Admin UI:** http://localhost:8080/admin/  
- **Device API base:** http://localhost:8080/api/v1/devices  

**Bootstrap admin** (created only if no administrators exist yet):

- Email: `admin@smartac.local`  
- Password: `ChangeMe!Admin2024`  

Change these in `application.properties` (`app.bootstrap.email`, `app.bootstrap.password`) or override via your deployment’s configuration mechanism.

For public deployments, set **`app.base-url`** to your HTTPS origin so invitation and password-reset links are correct.

---

## 4. Docker

The `Dockerfile` performs a multi-stage build (Maven + tests), then runs a slim JRE image as a non-root user.

### 4.1 Build the image

From the project root:

```bash
docker build -t smart-ac-poc:latest .
```

### 4.2 Run the container

The image defaults to connecting to a database on the **host** (not inside Docker):

- `MYSQL_HOST=host.docker.internal`  
- `MYSQL_PORT=3307`  
- Database/user/password defaults match `application.properties` / image `ENV` (override as needed).

**Windows / macOS (Docker Desktop):**

```powershell
docker run -d --name smartac-app -p 8080:8080 `
  --add-host=host.docker.internal:host-gateway `
  -m 3g `
  smart-ac-poc:latest
```

**Linux:** `host.docker.internal` is not always defined. Use your host IP or add:

`--add-host=host.docker.internal:host-gateway` (recent Docker) or set `-e MYSQL_HOST=<host LAN IP>`.

Override database settings:

```powershell
docker run -d --name smartac-app -p 8080:8080 `
  --add-host=host.docker.internal:host-gateway `
  -e MYSQL_HOST=host.docker.internal `
  -e MYSQL_PORT=3307 `
  -e MYSQL_DATABASE=smartac `
  -e MYSQL_USER=smartac `
  -e MYSQL_PASSWORD=your_secure_password `
  -m 3g `
  smart-ac-poc:latest
```

The image sets a **2 GiB** max heap by default (`JAVA_OPTS`). The **`-m 3g`** example gives the process enough cgroup memory for heap plus non-heap use. To change heap:

```powershell
docker run ... -e JAVA_OPTS="-Xmx4g -Xms512m -XX:+ExitOnOutOfMemoryError" ...
```

Stop and remove a previous container before reusing the name:

```powershell
docker stop smartac-app
docker rm smartac-app
```

---

## 5. Production-oriented notes

- Run behind **HTTPS** (reverse proxy or platform ingress).  
- Use a **managed database** with backups; avoid relying on `ddl-auto=update` in production.  
- Configure **SMTP** if you want password-reset and invitation emails sent; otherwise links may only appear in logs when dev logging is enabled.  
- Restrict database credentials and rotate the bootstrap admin password after first login.

---

## 6. Troubleshooting

| Symptom | What to check |
|---------|----------------|
| Cannot connect to database | Host/port/firewall; `MYSQL_*` values; MariaDB listening on `0.0.0.0` or the right interface. |
| Works locally but not in Docker | From the container, `host.docker.internal` (or `MYSQL_HOST`) must reach the DB; on Linux, set `MYSQL_HOST` to a reachable IP. |
| Port 8080 already in use | Stop the other process or map another port: `docker run -p 8081:8080 ...`. |
| `OutOfMemoryError: Java heap space` | Increase `JAVA_OPTS` `-Xmx` and container `-m` together. |
| Admin login page unstyled | Hard-refresh the browser; ensure static resources are served (same origin as the app). |

---

## 7. Further reading

- [API_GUIDE.md](API_GUIDE.md) — API overview  
- [DEVICE_API.md](DEVICE_API.md) — Device registration and readings  
- [ARCHITECTURE.md](ARCHITECTURE.md) — System design and trade-offs  
