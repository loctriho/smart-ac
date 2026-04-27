# Build (Java 21, Maven cached layers)
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# 1) Dependencies (cache-friendly)
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

# 2) Build
COPY src ./src
RUN mvn -q package

# Run (smaller JRE image, non-root)
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN useradd -r -u 10001 -g root appuser
USER 10001

COPY --from=build /workspace/target/smart-ac-poc-1.0.0-SNAPSHOT.jar /app/app.jar

EXPOSE 8080

# Default heap similar to a typical local IDE run (~2 GiB). Override for stress tests, e.g.
#   -e JAVA_OPTS="-Xmx4g -Xms512m -XX:+ExitOnOutOfMemoryError -XX:+UseStringDeduplication"
# Give the container at least ~512 MiB above -Xmx for metaspace / native (e.g. `docker run -m 3g`).
ENV JAVA_OPTS="-Xmx2g -Xms256m -XX:+ExitOnOutOfMemoryError -XX:+UseStringDeduplication"

# Defaults for running the app container against an *external* MariaDB/MySQL (not containerized).
# Override any of these with `docker run -e MYSQL_HOST=... -e MYSQL_PORT=...` etc.
ENV MYSQL_HOST="host.docker.internal" \
    MYSQL_PORT="3307" \
    MYSQL_DATABASE="smartac" \
    MYSQL_USER="root" \
    MYSQL_PASSWORD="root"

ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]
