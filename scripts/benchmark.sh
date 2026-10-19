#!/usr/bin/env bash
# Local benchmark suite: builds the benchmark module (and what it depends on) from this checkout,
# starts PostgreSQL and Redpanda in Testcontainers, runs the scenarios in every mode and writes
# target/benchmark/benchmark.{json,md} under modules/workflow-benchmark.
#
#   scripts/benchmark.sh                          # the full suite: 4 modes x 6 scenarios x 3 reps (~1-2 h)
#   scripts/benchmark.sh -Dsuite.scale=0.01 -Dsuite.reps=1    # a smoke run (a few minutes)
#   scripts/benchmark.sh -Dsuite.modes=memory,jpa -Dsuite.scenarios=sequential,sync
#   scripts/benchmark.sh -Dsuite.engine.workflow.timeout-scan-interval-ms=1000 -Dsuite.scenarios=timers
#
# Every -D is passed to the suite JVM; see SuiteMain for the knobs. Needs Docker and JDK 21.
# It never installs anything into ~/.m2: the reactor build (-am) packages this checkout's engine.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULE="$ROOT/modules/workflow-benchmark"
OUT="${SUITE_OUT:-$MODULE/target/benchmark}"
HEAP="${SUITE_HEAP:-6g}"

if [[ "${SUITE_SKIP_BUILD:-}" != "1" ]]; then
  # package (not install): the reactor resolves the engine from this checkout's target/ dirs, and the
  # classpath file lists exactly those jars. A plain classpath rather than the Boot fat jar, because
  # Testcontainers cannot extract its container scripts from a nested jar.
  (cd "$ROOT" && mvn -q -Pbenchmark -pl modules/workflow-benchmark -am -DskipTests -Djacoco.skip=true \
    package dependency:build-classpath -Dmdep.outputFile=target/suite.classpath -Dmdep.includeScope=runtime)
fi

CLASSPATH_FILE="$MODULE/target/suite.classpath"
[[ -f "$CLASSPATH_FILE" ]] || { echo "No $CLASSPATH_FILE: run without SUITE_SKIP_BUILD first" >&2; exit 1; }
CP="$MODULE/target/classes:$(cat "$CLASSPATH_FILE")"

# What the JVM cannot find out for itself, recorded alongside the numbers.
COMMIT="$(cd "$ROOT" && git rev-parse --short HEAD)$(cd "$ROOT" && git diff --quiet HEAD -- modules || echo '-dirty')"
VERSION="$(cd "$ROOT" && git describe --tags --always 2>/dev/null || echo unknown)"
if [[ "$(uname)" == "Darwin" ]]; then
  MACHINE="$(sysctl -n machdep.cpu.brand_string), $(sysctl -n hw.ncpu) cores ($(sysctl -n hw.perflevel0.physicalcpu 2>/dev/null || echo '?') performance + $(sysctl -n hw.perflevel1.physicalcpu 2>/dev/null || echo '?') efficiency), $(( $(sysctl -n hw.memsize) / 1073741824 )) GB RAM, macOS $(sw_vers -productVersion)"
else
  MACHINE="$(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | xargs), $(nproc) cores, $(( $(grep MemTotal /proc/meminfo | awk '{print $2}') / 1048576 )) GB RAM, $(uname -sr)"
fi
DOCKER_MEM_GB="$(( $(docker info --format '{{.MemTotal}}') / 1073741824 ))"
DOCKER="$(docker info --format 'Docker {{.ServerVersion}} ({{.OperatingSystem}}), {{.NCPU}} CPUs'), ${DOCKER_MEM_GB} GB"
OTHER_JAVA="$(pgrep -f java | wc -l | xargs)"
OTHER_CONTAINERS="$(docker ps -q | wc -l | xargs)"
BACKGROUND="load average $(uptime | sed 's/.*load averages*: //'); ${OTHER_JAVA} java processes and ${OTHER_CONTAINERS} containers already running before the suite started"

mkdir -p "$OUT"
echo "Machine: $MACHINE"
echo "Background: $BACKGROUND"

exec java -Xms"$HEAP" -Xmx"$HEAP" -XX:+UseG1GC \
  -Dsuite.out="$OUT" \
  -Dsuite.commit="$COMMIT" -Dsuite.version="$VERSION" \
  -Dsuite.machine="$MACHINE" -Dsuite.docker="$DOCKER" -Dsuite.background="$BACKGROUND" \
  "$@" \
  -cp "$CP" io.mateu.workflowbench.suite.SuiteMain
