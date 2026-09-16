# syntax=docker/dockerfile:1.7
#
# FNPH Kaduna Telepsychiatry backend, production image.
#
#   docker build -t fnph-backend .
#
# Two stages: Maven builds the jar, a slim JRE runs it as a non-root user.

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Dependencies first, so a code change does not re-download the world.
COPY pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src src
# Tests run in CI and on the developer's machine; the image build only packages.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
 && cp target/*.jar /workspace/app.jar

# ---------- run ----------
FROM eclipse-temurin:21-jre-jammy

# fontconfig and fonts: the PDF renderer (prescriptions, investigation
# requests) needs real fonts. curl: the container health check.
RUN apt-get update \
 && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system fnph && useradd --system --gid fnph --home /app fnph \
 && mkdir -p /app /var/lib/fnph/storage \
 && chown -R fnph:fnph /app /var/lib/fnph

WORKDIR /app
COPY --from=build --chown=fnph:fnph /workspace/app.jar app.jar
USER fnph

ENV SPRING_PROFILES_ACTIVE=prod \
    SERVER_PORT=8080 \
    STORAGE_ROOT=/var/lib/fnph/storage \
    TZ=UTC \
    JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

# Flyway runs every migration on first start, so give it time.
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
