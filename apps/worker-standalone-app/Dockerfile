# Runtime-only, from a jar the reactor already built:
#
#   mvn -DskipTests install -pl apps/worker-standalone-app -am
#   docker build -t miguelperezcolom/worker-standalone-app:<tag> apps/worker-standalone-app
#
# It used to build the jar itself, in a Maven stage, and could not: this app depends on
# io.mateu.workflow:test-worker:1.0-SNAPSHOT (and, before it, sample-worker), and those modules are
# never published anywhere — `mvn dependency:go-offline` inside the image has no repository to find
# them in and fails on the first line it runs. Layered on top, the COPY named `app-*.jar` while the
# artifact is `worker-standalone-app-*.jar`, so even a resolvable build would have found no jar to
# copy. Neither had ever run: the image on Docker Hub was pushed by hand from a laptop.
#
# This is the same shape as demo/*/Dockerfile.runtime, which is what the images that do get built
# are built from.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S -G app -u 10001 app
WORKDIR /opt/app
COPY --chown=app:app target/worker-standalone-app-*.jar app.jar

# ── Server ────────────────────────────────────────────────────────────────────
ENV SERVER_PORT=8080

# ── Kafka ─────────────────────────────────────────────────────────────────────
ENV KAFKA_BROKERS=localhost:9092

# ── Database ──────────────────────────────────────────────────────────────────
# Holds the tasks this worker was given and the replies someone canned by hand. DB_PASSWORD must
# be provided at runtime (no default). For a worker with no database at all — scenarios from
# TEST_CONFIG only, nothing surviving a restart — run it with SPRING_PROFILES_ACTIVE=memory.
ENV DB_URL=jdbc:postgresql://localhost:5432/workflow \
    DB_USERNAME=workflow \
    DB_DRIVER=org.postgresql.Driver \
    JPA_DIALECT=org.hibernate.dialect.PostgreSQLDialect \
    DDL_AUTO=update \
    DB_POOL_SIZE=8 \
    DB_CONNECTION_TIMEOUT=20000

# ── Worker ────────────────────────────────────────────────────────────────────
# How long a task takes when the scenario does not say.
ENV WORKER_TASK_DURATION=2s

# Writing only to /tmp, so the image satisfies a deployment's readOnlyRootFilesystem.
ENV JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/tmp

USER app
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://127.0.0.1:${SERVER_PORT}/actuator/health || exit 1

# No --spring.profiles.active=prod: there has never been an application-prod file to activate.
ENTRYPOINT ["java", "-jar", "app.jar"]
