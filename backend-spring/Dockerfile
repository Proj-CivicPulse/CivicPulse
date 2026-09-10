# backend-spring — production image.
#
# A Dockerfile rather than Railway's Nixpacks auto-detect, for two reasons:
#   1. This service needs Java 21 (see <java.version> in pom.xml, and Spring
#      Boot 4). Nixpacks picks a JDK on its own, and a silent default-version
#      change is a build break with a confusing error.
#   2. The build MUST skip tests. `./mvnw verify` here is a real integration
#      test that runs the Flyway migrations against whatever DB_URL points at
#      — it is not something a deploy pipeline should ever trigger.
#
# Railway uses this automatically when it is present in the service root.

# --- Stage 1: build ---------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build

WORKDIR /build

# The Maven wrapper is committed deliberately (see the root .gitignore), so the
# build needs no preinstalled Maven and the version is pinned by
# .mvn/wrapper/maven-wrapper.properties.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw

# Resolve dependencies as their own layer, keyed on pom.xml alone. Source
# changes then rebuild without re-downloading the world on every deploy.
#
# Non-fatal on purpose: go-offline is only a cache warm-up, and it is known to
# trip over plugin/annotation-processor resolution on some projects. Anything
# it genuinely fails to fetch is fetched again by the package step below, which
# IS fatal — so a real dependency problem still fails the build, just one layer
# later and with a clearer error.
RUN ./mvnw -B dependency:go-offline || true

COPY src/ src/

# -DskipTests, not -Dmaven.test.skip: the tests still compile (so a broken test
# source is caught here) but do not run, and therefore never touch a database.
RUN ./mvnw -B -DskipTests clean package

# --- Stage 2: runtime -------------------------------------------------------
FROM eclipse-temurin:21-jre

WORKDIR /app

# Run as a non-root user. Nothing here writes to disk, so no ownership beyond
# the jar itself is needed.
RUN useradd --system --create-home --shell /usr/sbin/nologin appuser
USER appuser

# Wildcard so the version in pom.xml can change without editing this file.
COPY --from=build --chown=appuser:appuser /build/target/*.jar app.jar

# Informational only — Railway assigns the real port via PORT, which
# application.yaml binds ahead of SERVER_PORT.
EXPOSE 8080

# MaxRAMPercentage keeps the heap inside the container's memory limit. Without
# it the JVM sizes the heap against the host's total RAM and gets OOM-killed on
# a small instance.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
