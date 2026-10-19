package io.mateu.workflow.maven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpecValidatorTest {

    private final SpecValidator validator = new SpecValidator();

    private JsonNode load(String resource) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            ObjectMapper mapper = resource.endsWith(".yaml") || resource.endsWith(".yml")
                    ? new YAMLMapper() : new ObjectMapper();
            return mapper.readTree(in);
        }
    }

    private List<String> validate(SpecValidator.Kind kind, String resource) throws Exception {
        return validator.validate(kind, load(resource));
    }

    @Test
    void validWorkflowsPass() throws Exception {
        assertThat(validate(SpecValidator.Kind.WORKFLOW, "/valid/workflows/sequential.json")).isEmpty();
        assertThat(validate(SpecValidator.Kind.WORKFLOW, "/valid/workflows/nightly.yaml")).isEmpty();
        assertThat(validate(SpecValidator.Kind.WORKFLOW, "/valid/workflows/booking-events.yaml")).isEmpty();
    }

    private static final String USER_TASK_WORKFLOW = """
            name: Expenses
            steps:
              - id: start
                type: START
                name: Start
              - id: approve
                type: USER_TASK
                name: Approve
                formId: approval
                preconditionStepId: start
            %s
              - id: review
                type: USER_TASK
                name: Review
                formId: approval
                preconditions:
                  - stepId: approve
                    expression: "false"
            """;

    private List<String> userTask(String approveExtra) throws Exception {
        return validator.validate(SpecValidator.Kind.WORKFLOW,
                new YAMLMapper().readTree(USER_TASK_WORKFLOW.formatted(approveExtra.indent(4).stripTrailing())));
    }

    @Test
    void aUserTasksAssignmentAndEscalationAreValidatedAsTheEngineDoes() throws Exception {
        assertThat(userTask("""
                assignment:
                  assignee: "${manager}"
                  candidateGroups: [finance]
                  priority: 80
                  dueDate: P3D
                escalation:
                  - after: PT48H
                    to: {candidateGroups: [managers]}
                    publishEvent:
                      destination: notifications
                      type: com.acme.task.escalated
                      payload: {level: "${escalation.level}"}
                      key: "${businessKey}"
                  - after: P5D
                    escalateTo: review""")).isEmpty();
        assertThat(userTask("""
                escalation:
                  - after: P5D
                    escalateTo: nowhere""")).anyMatch(v -> v.contains("unknown step 'nowhere'"));
        assertThat(userTask("""
                assignment:
                  priority: 150""")).anyMatch(v -> v.contains("priority"));
        assertThat(userTask("""
                assignment:
                  assignee: "${manager +}\"""")).anyMatch(v -> v.contains("assignment"));
        assertThat(userTask("""
                escalation:
                  - after: P1D
                    to: {assignee: "${boss +}"}""")).anyMatch(v -> v.contains("escalation[0].to"));
        assertThat(userTask("""
                escalation:
                  - after: P1D
                    publishEvent:
                      destination: d
                      type: t
                      payloadTemplate: "${1 +}\"""")).anyMatch(v -> v.contains("publishEvent.payloadTemplate"));
        assertThat(userTask("""
                escalation:
                  - after: P1D
                    publishEvent: {destination: d, type: t}
                forEach:
                  items: lines""")).isEmpty();
        assertThat(userTask("""
                deadline:
                  date: "${checkIn}"
                escalation:
                  - after: P1D""")).anyMatch(v -> v.contains("deadline"));
    }

    @Test
    void eventTriggersAndOnEventAreValidated() throws Exception {
        var doc = new YAMLMapper().readTree("""
                name: Bad triggers
                triggers:
                  - id: t
                    destination: bookings
                    type: com.acme.booking.created
                    filter: "data.nights >"
                  - id: t
                    destination: "book ings"
                    type: x
                    businessKey: "${unterminated"
                steps:
                  - id: start
                    type: START
                    name: Start
                  - id: act
                    type: ACTION
                    name: Act
                    topic: work
                    preconditionStepId: start
                    onEvent:
                      destination: payments
                      type: com.acme.payment.captured
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, doc))
                .anySatisfy(v -> assertThat(v).contains("triggers[0] filter"))
                .anySatisfy(v -> assertThat(v).contains("triggers[1]: 'book ings' is not a valid destination name"))
                .anySatisfy(v -> assertThat(v).contains("triggers[1] businessKey"))
                .anySatisfy(v -> assertThat(v).isEqualTo("triggers: trigger id 't' is declared more than once"))
                .anySatisfy(v -> assertThat(v).contains("Step 'act' declares onEvent, which only a WAIT_FOR_MESSAGE reads"));
    }

    @Test
    void anAwaitReplyIsValidated() throws Exception {
        var valid = new YAMLMapper().readTree("""
                name: Ask and wait
                steps:
                  - id: start
                    type: START
                    name: Start
                  - id: ask
                    type: PUBLISH_EVENT
                    name: Ask
                    preconditionStepId: start
                    timeout: PT30S
                    onTimeoutStepId: no-reply
                    event:
                      destination: quote-requests
                      type: com.acme.quote.requested
                    awaitReply:
                      messageName: quote-ready
                      correlationExpression: businessKey
                      onEvent:
                        destination: quote-replies
                        type: com.acme.quote.ready
                        correlationKey: "${data.requestId}"
                  - id: no-reply
                    type: ACTION
                    name: No reply
                    topic: work
                    preconditions:
                      - stepId: ask
                        expression: "false"
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, valid)).isEmpty();

        var invalid = new YAMLMapper().readTree("""
                name: Bad replies
                steps:
                  - id: start
                    type: START
                    name: Start
                  - id: ask
                    type: HTTP_CALL
                    name: Ask
                    preconditionStepId: start
                    messageName: quote-ready
                    http:
                      url: https://pricing.example.com/quotes
                    awaitReply:
                      messageName: quote-ready
                      correlationExpression: "businessKey +"
                      onEvent:
                        destination: "quote replies"
                        type: com.acme.quote.ready
                        correlationKey: "${unterminated"
                  - id: act
                    type: ACTION
                    name: Act
                    topic: work
                    preconditionStepId: ask
                    awaitReply:
                      messageName: m
                      correlationExpression: businessKey
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, invalid))
                .anySatisfy(v -> assertThat(v).contains("awaitReply.correlationExpression"))
                .anySatisfy(v -> assertThat(v).contains("awaitReply.onEvent: 'quote replies' is not a valid destination name"))
                .anySatisfy(v -> assertThat(v).contains("awaitReply.onEvent correlationKey"))
                .anySatisfy(v -> assertThat(v).contains("only an HTTP_CALL or a PUBLISH_EVENT awaits a reply"))
                .noneSatisfy(v -> assertThat(v).contains("declares messageName"));

        // Wait fields on a request step itself were always ignored: a warning, never a build failure.
        var misplaced = new YAMLMapper().readTree("""
                name: Old definition
                steps:
                  - id: start
                    type: START
                    name: Start
                  - id: ask
                    type: HTTP_CALL
                    name: Ask
                    preconditionStepId: start
                    messageName: quote-ready
                    correlationExpression: businessKey
                    http:
                      url: https://pricing.example.com/quotes
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, misplaced)).isEmpty();
        assertThat(validator.warnings(SpecValidator.Kind.WORKFLOW, misplaced))
                .contains("Step 'ask' declares messageName, which an HTTP_CALL ignores; to wait for a reply,"
                        + " put it inside awaitReply.");
    }

    @Test
    void loopsAndForEachAreValidatedWithTheEnginesRules() throws Exception {
        var valid = new YAMLMapper().readTree("""
                name: Loops
                steps:
                  - { id: start, type: START, name: Start }
                  - id: reserve
                    type: ACTION
                    name: Reserve
                    topic: work
                    preconditionStepId: start
                    forEach: { items: rooms, as: room, mode: sequential, output: res, completion: "completedIterations > 1" }
                  - id: poll
                    type: HTTP_CALL
                    name: Poll
                    preconditionStepId: reserve
                    http: { url: "https://example.com/status" }
                    repeat: { until: "status == 'DONE'", maxIterations: 5, delay: PT10S }
                  - id: review
                    type: ACTION
                    name: Review
                    topic: work
                    preconditionStepId: poll
                    loopBack: { to: reserve, while: "verdict == 'REWORK'", maxIterations: 3 }
                  - { id: end, type: END, name: End, preconditionStepId: review }
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, valid)).isEmpty();

        var invalid = new YAMLMapper().readTree("""
                name: Bad loops
                steps:
                  - { id: start, type: START, name: Start }
                  - id: fork
                    type: FORK
                    name: Fork
                    preconditionStepId: start
                    forEach: { items: "rooms +" }
                  - id: poll
                    type: ACTION
                    name: Poll
                    topic: work
                    preconditionStepId: fork
                    repeat: { until: "x ==", maxIterations: 0 }
                  - id: review
                    type: ACTION
                    name: Review
                    topic: work
                    preconditionStepId: poll
                    loopBack: { to: end, maxIterations: 2 }
                  - { id: end, type: END, name: End, preconditionStepId: review }
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, invalid))
                .anySatisfy(v -> assertThat(v).contains("FORK step cannot run once per item"))
                .anySatisfy(v -> assertThat(v).contains("forEach.items"))
                .anySatisfy(v -> assertThat(v).contains("repeat.until"))
                .anySatisfy(v -> assertThat(v).contains("maxIterations"))
                .anySatisfy(v -> assertThat(v).contains("not upstream"));

        var awaiting = new YAMLMapper().readTree("""
                name: Each awaits
                steps:
                  - { id: start, type: START, name: Start }
                  - id: ask
                    type: HTTP_CALL
                    name: Ask
                    preconditionStepId: start
                    http: { url: "https://example.com/quotes" }
                    awaitReply: { messageName: quote, correlationExpression: businessKey }
                    forEach: { items: suppliers }
                  - { id: end, type: END, name: End, preconditionStepId: ask }
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, awaiting))
                .anySatisfy(v -> assertThat(v).contains("forEach together with awaitReply"));
    }

    @Test
    void aTriggerWithoutATypeFailsTheSchema() throws Exception {
        var doc = new YAMLMapper().readTree("""
                name: No type
                triggers:
                  - destination: bookings
                steps:
                  - id: start
                    type: START
                    name: Start
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, doc)).anySatisfy(v -> assertThat(v).contains("type"));
    }

    @Test
    void eventFilesValidateInBothFormsWithTheEnginesTriggerRules() throws Exception {
        assertThat(validate(SpecValidator.Kind.EVENTS, "/valid/workflows/events.yaml")).isEmpty();
        var keyed = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().readTree("""
                triggers:
                  - destination: bookings
                    type: com.acme.booking.*
                    workflow: booking
                """);
        assertThat(validator.validate(SpecValidator.Kind.EVENTS, keyed)).isEmpty();
        assertThat(SpecValidator.eventEntries(keyed)).hasSize(1);

        var bad = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().readTree("""
                - destination: bookings
                  type: com.acme.booking.created
                - id: web
                  destination: "book ings"
                  type: t
                  workflow: booking
                - id: web
                  destination: bookings
                  type: t
                  workflow: booking
                - id: web
                  destination: bookings
                  type: t
                  workflow: other
                - id: "-bad"
                  destination: bookings
                  type: t
                  workflow: booking
                  extra: 1
                """);
        var violations = validator.validate(SpecValidator.Kind.EVENTS, bad);
        assertThat(violations)
                .anySatisfy(v -> assertThat(v).contains("workflow"))
                .anySatisfy(v -> assertThat(v).contains("entry 1").contains("not a valid destination"))
                .anySatisfy(v -> assertThat(v).contains("workflow 'booking'").contains("trigger id 'web' is declared more than once"))
                .noneSatisfy(v -> assertThat(v).contains("workflow 'other'"))
                .anySatisfy(v -> assertThat(v).contains("entry 4").contains("not a valid trigger id"))
                .anySatisfy(v -> assertThat(v).contains("extra"));
    }

    @Test
    void definitionTagsFollowTheEnginesRules() throws Exception {
        var mapper = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper();
        var good = mapper.readTree("""
                id: tagged
                name: Tagged
                tags: [billing, team:payments]
                steps:
                  - {id: start, type: START, name: Start}
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, good)).isEmpty();

        var bad = mapper.readTree("""
                id: tagged
                name: Tagged
                tags: [billing, Billing, "bad tag"]
                steps:
                  - {id: start, type: START, name: Start}
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, bad))
                .anySatisfy(v -> assertThat(v).contains("tag 'Billing' is declared more than once"))
                .anySatisfy(v -> assertThat(v).contains("bad tag"));
    }

    @Test
    void searchAttributesFollowTheEnginesRules() throws Exception {
        var mapper = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper();
        var good = mapper.readTree("""
                id: indexed
                name: Indexed
                searchAttributes:
                  customerId: "${customerId}"
                  amount: {value: "${total}", type: number}
                  region: {value: "${address.region}"}
                steps:
                  - {id: start, type: START, name: Start}
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, good)).isEmpty();

        var bad = mapper.readTree("""
                id: indexed
                name: Indexed
                searchAttributes:
                  amount: {value: "${total}", type: money}
                  fixed: "always the same"
                  Region: "${r}"
                  region: "${r}"
                steps:
                  - {id: start, type: START, name: Start}
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, bad))
                .anySatisfy(v -> assertThat(v).contains("money"))
                .anySatisfy(v -> assertThat(v).contains("fixed"))
                .anySatisfy(v -> assertThat(v).contains("'region' is declared more than once"));
    }

    @Test
    void definitionRetentionFollowsTheEnginesRules() throws Exception {
        var mapper = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper();
        for (var retention : new String[]{"P30D", "forever", "{completed: P30D, error: P180D, compensationFailed: forever}"}) {
            var good = mapper.readTree("""
                    id: kept
                    name: Kept
                    retention: %s
                    steps:
                      - {id: start, type: START, name: Start}
                    """.formatted(retention));
            assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, good)).as(retention).isEmpty();
        }

        // The schema's pattern lets these through; the shared rules do not.
        for (var retention : new String[]{"P0D", "PT", "P"}) {
            var bad = mapper.readTree("""
                    id: kept
                    name: Kept
                    retention: %s
                    steps:
                      - {id: start, type: START, name: Start}
                    """.formatted(retention));
            assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, bad)).as(retention)
                    .anySatisfy(v -> assertThat(v).contains("retention"));
        }
        // And the schema refuses what is not even shaped like a duration, or a status that never ends.
        for (var retention : new String[]{"30d", "P1M", "{running: P1D}", "{error: P1M}", "{}"}) {
            var bad = mapper.readTree("""
                    id: kept
                    name: Kept
                    retention: %s
                    steps:
                      - {id: start, type: START, name: Start}
                    """.formatted(retention));
            assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, bad)).as(retention).isNotEmpty();
        }
    }

    @Test
    void cronFilesValidateInBothForms() throws Exception {
        assertThat(validate(SpecValidator.Kind.CRON, "/valid/workflows/cron.yaml")).isEmpty();
        var keyed = new YAMLMapper().readTree("""
                schedules:
                  - cron: "@daily"
                    workflow: nightly
                """);
        assertThat(validator.validate(SpecValidator.Kind.CRON, keyed)).isEmpty();
        assertThat(SpecValidator.cronEntries(keyed)).hasSize(1);
    }

    @Test
    void cronFileEntriesNeedCronAndWorkflowAndValidValues() throws Exception {
        var doc = new YAMLMapper().readTree("""
                - workflow: nightly
                - cron: "0 0 9 * * *"
                - cron: "61 0 9 * * *"
                  workflow: nightly
                  zone: Nowhere/Land
                  variables: {a: {nested: true}}
                """);
        var violations = validator.validate(SpecValidator.Kind.CRON, doc);
        assertThat(violations).anySatisfy(v -> assertThat(v).contains("cron"))
                .anySatisfy(v -> assertThat(v).contains("workflow"))
                .anySatisfy(v -> assertThat(v).contains("entry 2: '61 0 9 * * *' is not a valid cron expression"))
                .anySatisfy(v -> assertThat(v).contains("entry 2: 'Nowhere/Land' is not a valid time zone"))
                .anySatisfy(v -> assertThat(v).contains("/2/variables/a"));
    }

    @Test
    void definitionSchedulesAreValidated() throws Exception {
        var doc = new YAMLMapper().readTree("""
                name: Bad schedules
                schedules:
                  - cron: "0 0 9 * * *"
                    zone: Europe/Madrid
                  - cron: "0 9 * * *"
                    variables: {k: v}
                steps:
                  - id: start
                    type: START
                    name: Start
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, doc))
                .containsExactly("schedules[1]: '0 9 * * *' is not a valid cron expression"
                        + " (six fields, seconds first, e.g. \"0 0 9 * * MON-FRI\")");
    }

    @Test
    void scheduleIdsAreSlugsDeclaredOncePerList() throws Exception {
        var cron = new YAMLMapper().readTree("""
                - id: nightly
                  cron: "@daily"
                  workflow: nightly
                - id: nightly
                  cron: "@hourly"
                  workflow: nightly
                - id: "not a slug"
                  cron: "@hourly"
                  workflow: nightly
                """);
        assertThat(validator.validate(SpecValidator.Kind.CRON, cron))
                .anySatisfy(v -> assertThat(v).contains("entry 2: 'not a slug' is not a valid schedule id"))
                .anySatisfy(v -> assertThat(v).isEqualTo("schedule id 'nightly' is declared more than once"));

        var definition = new YAMLMapper().readTree("""
                name: Named schedules
                schedules:
                  - id: morning
                    cron: "0 0 9 * * *"
                  - id: morning
                    cron: "0 0 10 * * *"
                steps:
                  - id: start
                    type: START
                    name: Start
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, definition))
                .containsExactly("schedules: schedule id 'morning' is declared more than once");
    }

    /**
     * A definition carrying a hand-made diagram arrangement passes the build gate. The graph editor
     * writes `layout` into .ec files, and the schema root refuses anything it does not declare — so
     * without this the plugin would fail the build of every project whose workflows had been
     * arranged.
     */
    @Test
    void anArrangedWorkflowPasses() throws Exception {
        assertThat(validate(SpecValidator.Kind.WORKFLOW, "/valid/workflows/arranged.yaml")).isEmpty();
    }

    @Test
    void validFormPasses() throws Exception {
        assertThat(validate(SpecValidator.Kind.FORM, "/valid/forms/approval.json")).isEmpty();
    }

    @Test
    void validRulesPass() throws Exception {
        assertThat(validate(SpecValidator.Kind.RULE, "/valid/rules/expression.yaml")).isEmpty();
        assertThat(validate(SpecValidator.Kind.RULE, "/valid/rules/decision-table.json")).isEmpty();
    }

    @Test
    void validTaskContractPasses() throws Exception {
        assertThat(validate(SpecValidator.Kind.TASK, "/valid/tasks/greet.json")).isEmpty();
    }

    @Test
    void aTaskWithABadTypeAndANonConstantErrorCodeFails() throws Exception {
        var violations = validate(SpecValidator.Kind.TASK, "/invalid/tasks/bad-type-and-code.json");
        // An unknown attribute type and an error code that is not a Java identifier are both rejected.
        assertThat(violations).isNotEmpty();
        assertThat(String.join("\n", violations)).contains("amount").contains("code");
    }

    @Test
    void joinOnGuardedPreconditionWarnsButStaysValid() throws Exception {
        JsonNode document = load("/valid/workflows/join-on-guarded-branch.json");
        // Legal (fail-closed dataflow) → no violation…
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, document)).isEmpty();
        // …but exactly one warning pointing at the guarded branch.
        assertThat(validator.warnings(SpecValidator.Kind.WORKFLOW, document)).containsExactly(
                "JOIN 'join' waits on guarded step 'maybe' — if its guard is false the join"
                        + " never fires and the flow beyond it is cancelled.");
    }

    @Test
    void joinOnUnguardedPreconditionsProducesNoWarnings() throws Exception {
        JsonNode document = load("/valid/workflows/join-unguarded.json");
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, document)).isEmpty();
        assertThat(validator.warnings(SpecValidator.Kind.WORKFLOW, document)).isEmpty();
    }

    @Test
    void nonWorkflowKindsNeverWarn() throws Exception {
        assertThat(validator.warnings(SpecValidator.Kind.FORM, load("/valid/forms/approval.json"))).isEmpty();
        assertThat(validator.warnings(SpecValidator.Kind.RULE, load("/valid/rules/decision-table.json"))).isEmpty();
    }

    @Test
    void aCompensationStepNeedsNoWayInOfItsOwn() throws Exception {
        // It is declared on the step it undoes and started by the rollback pipeline. Requiring a
        // precondition here is what produced the anchor-with-a-false-guard, and an anchor written
        // without the guard is a compensation wired into the happy path.
        assertThat(validate(SpecValidator.Kind.WORKFLOW, "/valid/workflows/compensation.json")).isEmpty();
    }

    @Test
    void aStepNothingCanStartIsReported() throws Exception {
        assertThat(validate(SpecValidator.Kind.WORKFLOW, "/invalid/workflows/unreachable-step.json"))
                .anySatisfy(v -> assertThat(v).contains("'orphan'", "nothing would ever start it"));
    }

    @Test
    void duplicateStepIdIsReported() throws Exception {
        assertThat(validate(SpecValidator.Kind.WORKFLOW, "/invalid/workflows/duplicate-id.json"))
                .anySatisfy(v -> assertThat(v).contains("Duplicate step id 's1'"));
    }

    @Test
    void danglingReferenceAndBadCronAreReported() throws Exception {
        List<String> violations = validate(SpecValidator.Kind.WORKFLOW, "/invalid/workflows/dangling-and-cron.yaml");
        assertThat(violations).anySatisfy(v -> assertThat(v).contains("unknown precondition step 'nope'"));
        assertThat(violations).anySatisfy(v -> assertThat(v).contains("not a valid cron expression"));
    }

    @Test
    void badJexlAndSchemaViolationBothReported() throws Exception {
        List<String> violations = validate(SpecValidator.Kind.WORKFLOW, "/invalid/workflows/bad-jexl-and-schema.json");
        // schema: status not in the allowed enum
        assertThat(violations).anySatisfy(v -> assertThat(v).containsIgnoringCase("status"));
        // semantic: unparseable precondition expression
        assertThat(violations).anySatisfy(v -> assertThat(v).contains("invalid JEXL expression"));
    }

    @Test
    void badCorrelationExpressionJexlIsReported() throws Exception {
        List<String> violations = validate(SpecValidator.Kind.WORKFLOW, "/invalid/workflows/bad-correlation-jexl.json");
        assertThat(violations).anySatisfy(v -> assertThat(v)
                .contains("correlationExpression")
                .contains("invalid JEXL expression"));
        // The syntactically valid SEND_MESSAGE correlationExpression is not flagged.
        assertThat(violations).noneSatisfy(v -> assertThat(v).contains("step 'send' correlationExpression"));
    }

    @Test
    void formMissingFieldsIsReported() throws Exception {
        assertThat(validate(SpecValidator.Kind.FORM, "/invalid/forms/missing-fields.json"))
                .anySatisfy(v -> assertThat(v).containsIgnoringCase("fields"));
    }

    @Test
    void decisionTableArityIsReported() throws Exception {
        List<String> violations = validate(SpecValidator.Kind.RULE, "/invalid/rules/bad-arity.json");
        assertThat(violations).anySatisfy(v -> assertThat(v).contains("'when' must have 2 cells"));
        assertThat(violations).anySatisfy(v -> assertThat(v).contains("'then' must have 1 cells"));
    }

    @Test
    void ruleBadJexlIsReported() throws Exception {
        List<String> violations = validate(SpecValidator.Kind.RULE, "/invalid/rules/bad-jexl.yaml");
        assertThat(violations).anySatisfy(v -> assertThat(v).contains("invalid JEXL expression"));
    }

    @Test
    void preconditionsDeclaredAsLinksAreSeenByTheStructuralChecks() throws Exception {
        // Read only preconditionStepIds/preconditionStepId and every one of these steps looks like
        // it waits for nothing, which the roots rule reports as a step nothing would ever start.
        assertThat(validate(SpecValidator.Kind.WORKFLOW, "/valid/workflows/link-preconditions.json")).isEmpty();
    }

    @Test
    void anUnparseableConditionOnALinkIsAViolation() throws Exception {
        JsonNode document = new ObjectMapper().readTree("""
                {
                  "id": "bad-link-jexl", "name": "Bad link JEXL", "version": 1,
                  "steps": [
                    { "id": "start", "type": "START", "name": "Start" },
                    { "id": "next", "type": "ACTION", "name": "Next", "topic": "work",
                      "preconditions": [ { "stepId": "start", "expression": "amount >" } ] }
                  ]
                }
                """);

        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, document))
                .anySatisfy(violation -> assertThat(violation)
                        .contains("step 'next' precondition on 'start'")
                        .contains("invalid JEXL expression"));
    }

    private static JsonNode json(String text) throws Exception {
        return new ObjectMapper().readTree(text);
    }

    private static final String SYNC_BOOKING = """
            {"id": "sync", "name": "Sync", "version": 1,
             "syncInvocation": {"enabled": true, "onFailure": "REPLY_AFTER_COMPENSATION"},
             "steps": [
               {"id": "start", "type": "START", "name": "Start"},
               {"id": "choice", "type": "CHOICE", "name": "Choice", "preconditionStepId": "start"},
               {"id": "ok", "type": "REPLY", "name": "Ok", "replyVariables": ["bookingId"], "preconditionStepId": "choice"},
               {"id": "ko", "type": "REPLY", "name": "Ko", "replyExpression": "{'ok': false}", "preconditionStepId": "choice"},
               {"id": "end", "type": "END", "name": "End", "preconditionStepId": "ok"},
               {"id": "silent", "type": "END", "name": "Silent", "preconditionStepId": "choice"}
             ]}
            """;

    @Test
    void replyStepsOnDifferentChoiceBranchesPassWithAWarningForASilentEnd() throws Exception {
        var doc = json(SYNC_BOOKING);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, doc)).isEmpty();
        assertThat(validator.warnings(SpecValidator.Kind.WORKFLOW, doc)).anyMatch(w -> w.contains("silent"));
    }

    @Test
    void twoRepliesOnOnePathFailTheBuild() throws Exception {
        var doc = json("""
                {"id": "twice", "name": "Twice", "version": 1, "steps": [
                  {"id": "start", "type": "START", "name": "Start"},
                  {"id": "r1", "type": "REPLY", "name": "R1", "preconditionStepId": "start"},
                  {"id": "r2", "type": "REPLY", "name": "R2", "preconditionStepId": "r1"}
                ]}
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, doc)).anyMatch(v -> v.contains("same path"));
    }

    @Test
    void aSyncInvocableWorkflowWithoutAReplyFailsTheBuild() throws Exception {
        var doc = json("""
                {"id": "mute", "name": "Mute", "version": 1, "syncInvocation": {"enabled": true}, "steps": [
                  {"id": "start", "type": "START", "name": "Start"},
                  {"id": "end", "type": "END", "name": "End", "preconditionStepId": "start"}
                ]}
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, doc)).anyMatch(v -> v.contains("no REPLY step"));
    }

    @Test
    void aReplyWithBothSourcesOrABadExpressionFails() throws Exception {
        var both = json("""
                {"id": "both", "name": "Both", "version": 1, "steps": [
                  {"id": "start", "type": "START", "name": "Start"},
                  {"id": "r", "type": "REPLY", "name": "R", "preconditionStepId": "start",
                   "replyVariables": ["a"], "replyExpression": "b"}
                ]}
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, both)).isNotEmpty();
        var bad = json("""
                {"id": "bad", "name": "Bad", "version": 1, "steps": [
                  {"id": "start", "type": "START", "name": "Start"},
                  {"id": "r", "type": "REPLY", "name": "R", "preconditionStepId": "start", "replyExpression": "{{{"}
                ]}
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, bad)).anyMatch(v -> v.contains("replyExpression"));
    }

    @Test
    void aReplyTemplateWithABrokenExpressionFailsTheBuild() throws Exception {
        var doc = json("""
                {"id": "t", "name": "T", "version": 1, "steps": [
                  {"id": "start", "type": "START", "name": "Start"},
                  {"id": "r", "type": "REPLY", "name": "R", "preconditionStepId": "start",
                   "replyTemplate": {"ok": "${a}", "bad": ["${b +}"], "open": "x ${y"}}
                ]}
                """);
        var violations = validator.validate(SpecValidator.Kind.WORKFLOW, doc);
        assertThat(violations).anyMatch(v -> v.contains("replyTemplate") && (v.contains("bad") || v.contains("Unterminated")));
    }

    @Test
    void aPublishEventNeedsItsEventBlockAndValidTemplates() throws Exception {
        var missing = json("""
                {"id": "p", "name": "P", "version": 1, "steps": [
                  {"id": "start", "type": "START", "name": "Start"},
                  {"id": "pub", "type": "PUBLISH_EVENT", "name": "Pub", "preconditionStepId": "start"}
                ]}
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, missing)).isNotEmpty();
        var bad = json("""
                {"id": "p", "name": "P", "version": 1, "steps": [
                  {"id": "start", "type": "START", "name": "Start"},
                  {"id": "pub", "type": "PUBLISH_EVENT", "name": "Pub", "preconditionStepId": "start",
                   "event": {"destination": "d", "type": "t", "payload": {"x": "${a +}"}}}
                ]}
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, bad)).anyMatch(v -> v.contains("event.payload"));
        var good = json("""
                {"id": "p", "name": "P", "version": 1, "steps": [
                  {"id": "start", "type": "START", "name": "Start"},
                  {"id": "pub", "type": "PUBLISH_EVENT", "name": "Pub", "preconditionStepId": "start",
                   "event": {"destination": "d", "type": "t", "key": "${k}", "payload": {"x": "${a}"}}}
                ]}
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, good)).isEmpty();
    }

    @Test
    void anHttpCallIsCheckedForStructureSecretsAndTemplates() throws Exception {
        var good = json("""
                {"id": "h", "name": "H", "version": 1, "steps": [
                  {"id": "start", "type": "START", "name": "Start"},
                  {"id": "call", "type": "HTTP_CALL", "name": "Call", "preconditionStepId": "start",
                   "http": {"url": "https://api.x.com/o/${id}", "method": "POST",
                            "auth": {"type": "bearer", "token": "${secret:PARTNER}"},
                            "body": {"a": "${id}"}, "output": {"v": "body.x"}}}
                ]}
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, good)).isEmpty();
        var leaky = json("""
                {"id": "h", "name": "H", "version": 1, "steps": [
                  {"id": "start", "type": "START", "name": "Start"},
                  {"id": "call", "type": "HTTP_CALL", "name": "Call", "preconditionStepId": "start",
                   "http": {"url": "https://api.x.com", "auth": {"type": "bearer", "token": "sk_live_123"},
                            "body": {"a": "${id +}"}, "output": {"v": "body.("}}}
                ]}
                """);
        var violations = validator.validate(SpecValidator.Kind.WORKFLOW, leaky);
        assertThat(violations).anyMatch(v -> v.contains("auth.token"));
        assertThat(violations).anyMatch(v -> v.contains("http.body"));
        assertThat(violations).anyMatch(v -> v.contains("http.output.v"));
    }

    @Test
    void llmCallsAreCheckedByTheSharedRulesAndTheirTemplatesParsed() throws Exception {
        var good = json("""
                {"id": "l", "name": "L", "version": 1, "steps": [
                  {"id": "start", "type": "START", "name": "Start"},
                  {"id": "triage", "type": "LLM_CALL", "name": "Triage", "preconditionStepId": "start",
                   "llm": {"connection": "claude", "model": "claude-sonnet-5-5", "system": "You triage.",
                           "prompt": "${guest} wrote: ${complaint}", "maxTokens": 512,
                           "responseSchema": {"type": "object", "required": ["category"]},
                           "output": {"category": "json.category"}, "usageVariable": "usage"}}
                ]}
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, good)).isEmpty();
        var bad = json("""
                {"id": "l", "name": "L", "version": 1, "steps": [
                  {"id": "start", "type": "START", "name": "Start"},
                  {"id": "triage", "type": "LLM_CALL", "name": "Triage", "preconditionStepId": "start",
                   "llm": {"connection": "claude", "prompt": "${guest +} ${secret:KEY}", "system": "${(",
                           "output": {"category": "json.("}}},
                  {"id": "nollm", "type": "LLM_CALL", "name": "No block", "preconditionStepId": "start"}
                ]}
                """);
        var violations = validator.validate(SpecValidator.Kind.WORKFLOW, bad);
        assertThat(violations).anyMatch(v -> v.contains("must never reach a prompt"));
        assertThat(violations).anyMatch(v -> v.contains("llm.system"));
        assertThat(violations).anyMatch(v -> v.contains("llm.output.category"));
        assertThat(violations).anyMatch(v -> v.contains("'nollm' must define an llm block"));
    }

    @Test
    void agentCallsAreCheckedByTheSharedRulesTheirToolStepsAreReachableAndTheirSchemasAreSchemas() throws Exception {
        var good = json("""
                {"id": "a", "name": "A", "version": 1, "steps": [
                  {"id": "start", "type": "START", "name": "Start"},
                  {"id": "agent", "type": "AGENT_CALL", "name": "Agent", "preconditionStepId": "start",
                   "agent": {"connection": "claude", "system": "You help.", "goal": "Help ${customer}",
                     "tools": [
                       {"name": "lookup", "description": "Look up", "step": "lookup",
                        "inputSchema": {"type": "object", "properties": {"id": {"type": "string"}}}},
                       {"name": "fetch", "description": "Fetch", "http": {"method": "GET", "url": "https://x.example/${id}"}},
                       {"name": "refund", "description": "Refund", "process": "refund-flow", "outputVariables": ["refundId"],
                        "approval": {"formId": "approve"}}
                     ],
                     "output": {"schema": {"type": "object"}, "mapping": {"decision": "json.decision"}},
                     "maxSteps": 5, "toolConcurrency": 2, "onToolError": "report", "maxDuration": "PT5M"}},
                  {"id": "end", "type": "END", "name": "End", "preconditionStepId": "agent"},
                  {"id": "lookup", "type": "ACTION", "name": "Lookup"}
                ]}
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, good)).isEmpty();
        var bad = json("""
                {"id": "a", "name": "A", "version": 1, "steps": [
                  {"id": "start", "type": "START", "name": "Start"},
                  {"id": "agent", "type": "AGENT_CALL", "name": "Agent", "preconditionStepId": "start",
                   "agent": {"connection": "claude", "goal": "${(",
                     "tools": [
                       {"name": "lookup", "description": "Look up", "step": "end",
                        "inputSchema": {"type": "object", "properties": {"id": {"type": 7}}}},
                       {"name": "lookup", "description": "Twice", "task": "t@1"}
                     ],
                     "output": {"mapping": {"decision": "json.("}},
                     "maxSteps": 5}},
                  {"id": "end", "type": "END", "name": "End", "preconditionStepId": "agent"}
                ]}
                """);
        var violations = validator.validate(SpecValidator.Kind.WORKFLOW, bad);
        assertThat(violations).anyMatch(v -> v.contains("declares the tool 'lookup' twice"));
        assertThat(violations).anyMatch(v -> v.contains("a tool step must be one of"));
        assertThat(violations).anyMatch(v -> v.contains("used only as a tool"));
        var schemaBad = json("""
                {"id": "a", "name": "A", "version": 1, "steps": [
                  {"id": "start", "type": "START", "name": "Start"},
                  {"id": "agent", "type": "AGENT_CALL", "name": "Agent", "preconditionStepId": "start",
                   "agent": {"connection": "claude", "goal": "${(",
                     "tools": [{"name": "t", "description": "T", "task": "t@1",
                                "inputSchema": {"type": "object", "properties": {"id": {"type": 7}}}}],
                     "output": {"mapping": {"decision": "json.("}}, "maxSteps": 1}}
                ]}
                """);
        var schemaViolations = validator.validate(SpecValidator.Kind.WORKFLOW, schemaBad);
        assertThat(schemaViolations).anyMatch(v -> v.contains("agent.goal"));
        assertThat(schemaViolations).anyMatch(v -> v.contains("output.mapping.decision"));
        assertThat(schemaViolations).anyMatch(v -> v.contains("inputSchema is not a valid JSON Schema"));
    }

    @Test
    void momentsAreCheckedByTheSharedRules() throws Exception {
        var good = json("""
                {"id": "m", "name": "M", "version": 1, "steps": [
                  {"id": "start", "type": "START", "name": "Start"},
                  {"id": "wait", "type": "TIMER", "name": "Wait", "preconditionStepId": "start",
                   "until": {"date": "${checkinDate}", "offset": "-P3D", "at": "09:00", "zone": "${hotelZone}", "ifPast": "timeout"}},
                  {"id": "pay", "type": "WAIT_FOR_MESSAGE", "name": "Pay", "preconditionStepId": "wait",
                   "messageName": "paid", "correlationExpression": "bookingId", "deadline": "${checkinDate}"}
                ]}
                """);
        assertThat(validator.validate(SpecValidator.Kind.WORKFLOW, good)).isEmpty();
        var bad = json("""
                {"id": "m", "name": "M", "version": 1, "steps": [
                  {"id": "start", "type": "START", "name": "Start"},
                  {"id": "wait", "type": "TIMER", "name": "Wait", "preconditionStepId": "start",
                   "until": {"date": "${checkinDate +}", "offset": "3 days", "zone": "Mars/Olympus"}},
                  {"id": "pay", "type": "WAIT_FOR_MESSAGE", "name": "Pay", "preconditionStepId": "wait",
                   "messageName": "paid", "correlationExpression": "bookingId",
                   "deadline": {"date": "${checkinDate}", "ifPast": "fire"}}
                ]}
                """);
        var violations = validator.validate(SpecValidator.Kind.WORKFLOW, bad);
        assertThat(violations).anyMatch(v -> v.contains("until offset"));
        assertThat(violations).anyMatch(v -> v.contains("Mars/Olympus"));
        assertThat(violations).anyMatch(v -> v.contains("deadline has an unknown field 'ifPast'"));
    }
}
