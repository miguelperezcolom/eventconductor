// Chapter 12 — real runs in the workshop project (examples/workshop-hotel-booking). Nothing here needs the app:
// every command is a Maven run or a file read, cached across languages (FRESH=1 runs them again).
// The rename in `verify-gap` is undone by the same command line (git checkout), so the tree stays clean.

const DEF = 'src/main/resources/workflows/06-hotel-booking.ec.yaml';

export async function run(c) {
  c.term('iterations-src', "grep -n -B3 'iterations(' src/test/java/com/example/hotel/RealHandlersTest.java", { cache: true });
  c.term('mvn-test', 'mvn -B test', { cache: true, keep: /Tests run: .* -- in com|Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+$|BUILD (SUCCESS|FAILURE)/ });
  c.term('coverage', 'cat target/eventconductor-coverage/coverage.txt', { cache: true });
  c.term('verify-gap', `perl -pi -e 's/^(\\s+- id: )approve$/\${1}manager-approval/; s/(preconditionStepId): approve$/$1: manager-approval/' ${DEF} && mvn -B eventconductor:verify -Deventconductor.migration.from=HEAD; git checkout -- ${DEF}`,
    { cache: true, keep: /check-migration|ERROR \[approve\]|BUILD (SUCCESS|FAILURE)/ });
  c.term('verify-green', 'mvn -B verify -Deventconductor.migration.from=HEAD', { cache: true,
    keep: /EventConductor: .*validated|Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+$|EventConductor (migration check|workflow coverage|verify: every)|BUILD (SUCCESS|FAILURE)/ });
}
