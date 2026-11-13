#!/usr/bin/env bash
# Captures the REAL terminal output the "gate" and "editor" scenes show:
#   test-green / test-red     — mvn test in the gate project, before and after switching the forEach
#                               from compensateAll to continue (rooms no longer rolled back)
#   validate-green / validate-red — mvn eventconductor:validate, valid and with a dangling reference
# Writes build/out/*.txt, trimmed to the lines a viewer needs (nothing is rewritten).
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
cd "$here"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
bash scripts/prepare-app.sh >/dev/null
gate="$here/build/gate"; out="$here/build/out"; def="$gate/src/main/resources/workflows/hotel-booking.yaml"
mkdir -p "$out"
plugin="io.mateu.workflow:workflow-maven-plugin:1.0-SNAPSHOT:validate"
keep_test='Running com|Tests run:|FAILURE!|Expected process|BUILD (SUCCESS|FAILURE)'
keep_val='EventConductor|references unknown|BUILD (SUCCESS|FAILURE)'

(cd "$gate" && mvn -B "$plugin" 2>&1 || true) | grep -E "$keep_val" | sed -E "s#$gate/##" > "$out/validate-green.txt"
(cd "$gate" && mvn -B test 2>&1 || true) | grep -E "$keep_test" | grep -v '^\[ERROR\] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0$' | awk '!s[$0]++' > "$out/test-green.txt"
cp "$def" /tmp/promo-def.yaml
sed -i '' 's/onIterationFailure: compensateAll/onIterationFailure: continue/' "$def"
(cd "$gate" && mvn -B test 2>&1 || true) | grep -E "$keep_test" | grep -vE '^\[ERROR\] +HotelBookingTest|^\[ERROR\] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0$' | awk '!s[$0]++' > "$out/test-red.txt"
cp /tmp/promo-def.yaml "$def"
sed -i '' 's/preconditionStepId: rooms/preconditionStepId: room/' "$def"
(cd "$gate" && mvn -B "$plugin" 2>&1 || true) | grep -E "$keep_val" | sed -E "s#$gate/##" > "$out/validate-red.txt"
cp /tmp/promo-def.yaml "$def"
for f in "$out"/*.txt; do echo "== $(basename "$f")"; cat "$f"; done
