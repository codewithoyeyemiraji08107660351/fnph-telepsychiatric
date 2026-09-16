# Multi-stage: the build image carries Maven and the full JDK, the runtime
# image carries neither. A ~1.2 GB build image producing a ~400 MB runtime is
# worth the extra stage on a box where memory is the constraint.
FROM maven:3.9-amazoncorretto-21 AS build
WORKDIR /build

# Dependencies first, in their own layer. The pom changes rarely and the source
# changes constantly, so this layer survives most rebuilds and turns a
# four-minute build into a forty-second one.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
# Tests need a database, which the build container does not have. They run in
# CI or locally against the test schema, never here.
RUN mvn -B clean package -DskipTests

FROM amazoncorretto:21-alpine
WORKDIR /app

# Not root. A flaw in the application should not be a flaw with control of the
# container.
RUN addgroup -S fnph && adduser -S fnph -G fnph

COPY --from=build /build/target/*.jar app.jar

# Uploaded documents and issued PDFs live here, mounted from a named volume.
# Created and owned before the user drops in, or the first upload fails on a
# permission the running process cannot fix.
RUN mkdir -p /app/storage && chown -R fnph:fnph /app/storage /app

USER fnph
EXPOSE 8080

# MaxRAMPercentage rather than -Xmx: the JVM reads the container limit, so the
# heap follows the compose memory cap instead of being set twice in two places
# that can disagree.
#
# 70% of 2.5 GB is ~1.75 GB heap, leaving room for metaspace, thread stacks and
# direct buffers inside the same limit. Set it higher and the container is
# killed by the kernel rather than the JVM throwing OutOfMemoryError, which is
# much harder to diagnose.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

# ExitOnOutOfMemoryError so the container dies and restarts rather than limping
# on in a state where some requests work and some do not.

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]