# One image, three roles. The same artifact runs the orchestrator pods, the workers and the load
# driver, so a run spread over several hosts cannot accidentally compare different builds — which
# is the easiest way to produce a benchmark figure that means nothing.
#
# Build from the repository root:
#   docker build -f modules/workflow-benchmark/Dockerfile -t eventconductor-bench .
FROM maven:3.9.5-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -q -Pbenchmark -pl modules/workflow-benchmark -am -DskipTests -Djacoco.skip=true package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/modules/workflow-benchmark/target/workflow-benchmark-*-runnable.jar app.jar
# Every knob is a -Dbench.* property; pass them through BENCH_OPTS.
#   docker run -e BENCH_OPTS="-Dbench.role=pods -Dbench.kafka.brokers=kafka-host:9092" ...
ENV BENCH_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $BENCH_OPTS -jar app.jar"]
