# One image, three roles. The same artifact runs the orchestrator pods, the workers and the load
# driver, so a run spread over several hosts cannot accidentally compare different builds — which
# is the easiest way to produce a benchmark figure that means nothing.
#
# It packages a jar built outside rather than building one in a stage of its own. The jar is
# bytecode and cares nothing for the architecture it was produced on; only the JRE underneath
# does. That matters when the cluster is amd64 and the machine driving this is not: cross-building
# a Maven stage under emulation spends half an hour producing a jar identical to the one already
# on disk.
#
#   mvn -q -Pbenchmark -pl modules/workflow-benchmark -am -DskipTests package
#   docker buildx build --platform linux/amd64 -f modules/workflow-benchmark/Dockerfile \
#     -t <user>/eventconductor-bench:<tag> --push .
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY modules/workflow-benchmark/target/workflow-benchmark-*-runnable.jar app.jar
# Every knob is a -Dbench.* property; pass them through BENCH_OPTS.
ENV BENCH_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $BENCH_OPTS -jar app.jar"]
