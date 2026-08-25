# --- Build stage ---
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

# Dependencies resolve in their own layer: pom.xml changes far less often than
# src/, so ordinary code changes reuse this cached layer instead of
# re-downloading the whole dependency tree.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
# Tests are deliberately skipped in the image build. They are Testcontainers-
# based, so running them here would require a Docker daemon inside the build --
# and they already run as their own CI step against the same commit.
RUN mvn -B clean package -DskipTests

# --- Runtime stage ---
# JRE, not JDK: nothing at runtime needs a compiler. Alpine keeps the base
# small and its BusyBox userland already provides the wget used by HEALTHCHECK
# below, so no package needs installing.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Non-root. BusyBox adduser syntax; -S creates a system user with no password
# and no login shell.
RUN addgroup -S trademesh && adduser -S -G trademesh trademesh

COPY --from=build --chown=trademesh:trademesh /app/target/*.jar app.jar

USER trademesh

EXPOSE 8080

# /actuator/health is exposed by application.yml's management config and is the
# only actuator endpoint published. It returns 503 (not 200) when any component
# is DOWN -- including the datasource -- so the HTTP status alone is a
# sufficient check and the body does not need parsing.
#
# start-period covers JVM + Spring context startup, during which failures are
# not counted against the retry budget.
# 127.0.0.1 rather than localhost, deliberately: `localhost` can resolve to
# IPv6 ::1 first inside these images while the server binds the IPv4 wildcard,
# which reports a perfectly healthy container as unhealthy. That bit the
# frontend image during this phase's verification; using the literal address
# removes the dependency on resolver ordering here too.
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health || exit 1

# No configuration is baked in. Every environment-specific value is read from
# the environment at startup: SPRING_DATASOURCE_URL / _USERNAME / _PASSWORD,
# REDIS_HOST, REDIS_PORT, JWT_SECRET, JWT_EXPIRATION_MS, CORS_ALLOWED_ORIGINS.
# See application.yml for the defaults each falls back to.
ENTRYPOINT ["java", "-jar", "app.jar"]
