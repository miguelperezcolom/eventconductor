#!/usr/bin/env bash
# Builds the promo video's app: a copy of testbench/workflow-embedded (JPA/H2 + the console)
# with the hotel-booking definition, a hotel worker that refuses room 102; and the gate project (test kit only).
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
repo="$(cd "$here/../.." && pwd)"
build="$here/build/app"
rm -rf "$build" && mkdir -p "$(dirname "$build")"
cp -R "$repo/testbench/workflow-embedded" "$build"
rm -f "$build/src/main/resources/workflows/"*
cp -R "$here/app/src/." "$build/src/"
# The definition is imported from a directory at startup (not the classpath): a changed file then
# records a new version, as a deployment directory or a Git import would.
cp -R "$here/app/definitions" "$build/definitions"
printf '\nworkflow.directory-import.directories=./definitions\n' >> "$build/src/main/resources/application.properties"
# H2 on disk, so a restart keeps the history (the Versions scene needs two runs).
perl -pi -e 's#jdbc:h2:mem:workflow;DB_CLOSE_DELAY=-1#jdbc:h2:file:./data/workflow;DB_CLOSE_DELAY=-1#; s#workflow.cron-enabled=true#workflow.cron-enabled=false#' \
  "$build/src/main/resources/application.properties"
rm -f "$build/src/test/java/io/mateu/workflow/embedded/EmbeddedApplicationTests.java"
echo "app prepared in $build"
# The gate project: the definition, used by the test kit test.
gate="$here/build/gate"
rm -rf "$gate" && cp -R "$here/gate" "$gate"
mkdir -p "$gate/src/main/resources/workflows"
cp "$here/app/definitions/hotel-booking.yaml" "$gate/src/main/resources/workflows/"
echo "gate prepared in $gate"
