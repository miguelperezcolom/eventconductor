package io.mateu.workflow.application.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowDefinitionValidatorTest {

    private WorkflowDefinitionValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        validator = new WorkflowDefinitionValidator();
        validator.init();
    }

    private Step startStep() {
        return new Step("start", null, StepType.START, "Start", null, null, null, null, false, null, null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
    }

    /** An ACTION step hanging off the START entry point (the roots rule requires one). */
    private Step actionStep(String id) {
        return new Step(id, null, StepType.ACTION, "Step " + id, null, "start", null, null, false, "my-topic", null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
    }

    @Test
    void validDefinitionPassesValidation() {
        var wd = new WorkflowDefinition("wd-1", "Test Workflow", 1, "desc",  false, 0, false, null, 0, List.of(startStep(), actionStep("s1")));

        assertThatNoException().isThrownBy(() -> validator.validate(wd));
    }

    @Test
    void validCronExpressionPassesValidation() {
        var wd = new WorkflowDefinition("wd-1", "Test Workflow", 1, "desc",  false, 0, false, "0 0 9 * * MON-FRI", 0, List.of(startStep(), actionStep("s1")));

        assertThatNoException().isThrownBy(() -> validator.validate(wd));
    }

    @Test
    void invalidCronExpressionIsRejected() {
        var wd = new WorkflowDefinition("wd-1", "Test Workflow", 1, "desc",  false, 0, false, "not a cron", 0, List.of(startStep(), actionStep("s1")));

        assertThatThrownBy(() -> validator.validate(wd))
                .isInstanceOf(WorkflowDefinitionValidator.WorkflowDefinitionValidationException.class)
                .hasMessageContaining("not a cron");
    }

    @Test
    void schedulesWithZoneAndVariablesPassValidationAlongsideCronExpression() {
        var wd = new WorkflowDefinition("wd-1", "Test Workflow", 1, "desc", false, 0, false, "0 0 9 * * MON-FRI", 0,
                List.of(startStep(), actionStep("s1")))
                .withSchedules(List.of(
                        new io.mateu.workflow.domain.aggregates.CronSchedule("0 0 18 * * *", "Europe/Madrid",
                                java.util.Map.of("region", "EMEA")),
                        new io.mateu.workflow.domain.aggregates.CronSchedule("@daily")));

        assertThatNoException().isThrownBy(() -> validator.validate(wd));
    }

    @Test
    void scheduleWithInvalidCronOrZoneIsRejected() {
        var wd = new WorkflowDefinition("wd-1", "Test Workflow", 1, "desc", false, 0, false, null, 0,
                List.of(startStep(), actionStep("s1")))
                .withSchedules(List.of(
                        new io.mateu.workflow.domain.aggregates.CronSchedule("0 0 18 * * *", "Europe/Madrid", null),
                        new io.mateu.workflow.domain.aggregates.CronSchedule("0 9 * * *", "Nowhere/Land", null)));

        assertThatThrownBy(() -> validator.validate(wd))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schedules[1]: '0 9 * * *' is not a valid cron expression")
                .hasMessageContaining("schedules[1]: 'Nowhere/Land' is not a valid time zone")
                .hasMessageNotContaining("schedules[0]");
    }

    @Test
    void schedulesRoundTripThroughYamlWithScalarVariablesReadAsText() throws Exception {
        var yaml = """
                id: wd-1
                name: Test
                steps:
                  - id: start
                    type: START
                    name: Start
                schedules:
                  - cron: "0 0 9 * * *"
                    zone: Europe/Madrid
                    variables:
                      region: EMEA
                      copies: 2
                      urgent: true
                """;
        var mapper = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper();
        validator.validateSource(mapper.readTree(yaml), "inline");
        var wd = mapper.readValue(yaml, WorkflowDefinition.class);

        org.assertj.core.api.Assertions.assertThat(wd.schedules()).containsExactly(
                new io.mateu.workflow.domain.aggregates.CronSchedule("0 0 9 * * *", "Europe/Madrid",
                        java.util.Map.of("region", "EMEA", "copies", "2", "urgent", "true")));
        assertThatNoException().isThrownBy(() -> validator.validate(wd));
        // A definition that declares none does not grow a `schedules` key on export.
        org.assertj.core.api.Assertions.assertThat(new ObjectMapper().writeValueAsString(wd.withSchedules(null)))
                .doesNotContain("schedules");
    }

    @Test
    void aMisspelledScheduleFieldIsCaughtBySourceValidation() throws Exception {
        var yaml = """
                name: Test
                steps:
                  - id: start
                    type: START
                    name: Start
                schedules:
                  - cron: "0 0 9 * * *"
                    zon: Europe/Madrid
                """;
        var tree = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().readTree(yaml);

        assertThatThrownBy(() -> validator.validateSource(tree, "typo.yaml"))
                .isInstanceOf(WorkflowDefinitionValidator.WorkflowDefinitionValidationException.class)
                .hasMessageContaining("zon");
    }

    @Test
    void triggersAndAnOnEventWaitRoundTripThroughYamlAndPassValidation() throws Exception {
        var yaml = """
                id: wd-1
                name: Test
                triggers:
                  - destination: bookings
                    type: com.acme.booking.created
                    filter: "data.channel == 'web'"
                    businessKey: "booking-${data.bookingId}"
                    variables:
                      bookingId: "${data.bookingId}"
                      nights: 2
                  - id: any-booking
                    destination: bookings
                    type: com.acme.booking.*
                steps:
                  - id: start
                    type: START
                    name: Start
                  - id: wait
                    type: WAIT_FOR_MESSAGE
                    name: Wait for the payment
                    preconditionStepId: start
                    messageName: payment-captured
                    correlationExpression: bookingId
                    onEvent:
                      destination: payments
                      type: com.acme.payment.captured
                      correlationKey: "${data.bookingId}"
                """;
        var mapper = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper();
        validator.validateSource(mapper.readTree(yaml), "inline");
        var wd = mapper.readValue(yaml, WorkflowDefinition.class);
        assertThatNoException().isThrownBy(() -> validator.validate(wd));

        org.assertj.core.api.Assertions.assertThat(wd.identifiedTriggers())
                .extracting(io.mateu.workflow.domain.aggregates.EventTrigger::id)
                .containsExactly("com.acme.booking.created", "any-booking");
        org.assertj.core.api.Assertions.assertThat(wd.triggers().getFirst().variables())
                .containsEntry("bookingId", "${data.bookingId}").containsEntry("nights", 2);
        org.assertj.core.api.Assertions.assertThat(wd.steps().get(1).onEvent().correlationKey()).isEqualTo("${data.bookingId}");
        // No triggers key on export when there are none.
        var plain = new ObjectMapper().writeValueAsString(wd.withTriggers(null).withSteps(List.of(startStep())));
        org.assertj.core.api.Assertions.assertThat(plain).doesNotContain("triggers");
        // Nor an onEvent key on a step without one: what a definition version is hashed from is unchanged.
        org.assertj.core.api.Assertions.assertThat(io.mateu.core.infra.JsonSerializer.toJson(startStep()))
                .doesNotContain("onEvent");
    }

    @Test
    void aTriggerWithoutATypeABadFilterOrARepeatedIdIsRejected() {
        var wd = new WorkflowDefinition("wd-1", "Test", 1, "desc", false, 0, false, null, 0,
                List.of(startStep(), actionStep("s1")))
                .withTriggers(List.of(
                        new io.mateu.workflow.domain.aggregates.EventTrigger("t", "bookings", null, null, null, null),
                        new io.mateu.workflow.domain.aggregates.EventTrigger("t", "bookings", "x", "data.a >", null, null)));

        assertThatThrownBy(() -> validator.validate(wd))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("triggers[0]: a type is required")
                .hasMessageContaining("triggers[1] filter")
                .hasMessageContaining("trigger id 't' is declared more than once");
    }

    @Test
    void onEventOnAStepThatIsNotAWaitIsRejected() {
        var step = actionStep("s1").withOnEvent(new io.mateu.workflow.domain.aggregates.EventWait("payments", "x", null, null, null));
        var wd = new WorkflowDefinition("wd-1", "Test", 1, "desc", false, 0, false, null, 0, List.of(startStep(), step));

        assertThatThrownBy(() -> validator.validate(wd))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("declares onEvent, which only a WAIT_FOR_MESSAGE reads");
    }

    @Test
    void aMisspelledTriggerFieldIsCaughtBySourceValidation() throws Exception {
        var yaml = """
                name: Test
                steps:
                  - id: start
                    type: START
                    name: Start
                triggers:
                  - destination: bookings
                    type: com.acme.booking.created
                    filtr: "true"
                """;
        var tree = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().readTree(yaml);

        assertThatThrownBy(() -> validator.validateSource(tree, "typo.yaml"))
                .isInstanceOf(WorkflowDefinitionValidator.WorkflowDefinitionValidationException.class)
                .hasMessageContaining("filtr");
    }

    @Test
    void anAwaitReplyIsAcceptedOnARequestStepAndRejectedElsewhereBySourceValidation() throws Exception {
        var yaml = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper();
        var valid = yaml.readTree("""
                name: Ask
                steps:
                  - id: start
                    type: START
                    name: Start
                  - id: ask
                    type: PUBLISH_EVENT
                    name: Ask
                    preconditionStepId: start
                    timeout: PT30S
                    event: { destination: quotes, type: quote.requested }
                    awaitReply:
                      messageName: quote-ready
                      correlationExpression: businessKey
                      onEvent: { destination: quote-replies, type: quote.ready }
                """);
        assertThatNoException().isThrownBy(() -> validator.validateSource(valid, "ask.yaml"));

        var misplaced = yaml.readTree("""
                name: Ask
                steps:
                  - id: start
                    type: START
                    name: Start
                  - id: act
                    type: ACTION
                    name: Act
                    topic: work
                    preconditionStepId: start
                    awaitReply: { messageName: m, correlationExpression: businessKey }
                """);
        assertThatThrownBy(() -> validator.validateSource(misplaced, "act.yaml"))
                .isInstanceOf(WorkflowDefinitionValidator.WorkflowDefinitionValidationException.class);

        var incomplete = yaml.readTree("""
                name: Ask
                steps:
                  - id: start
                    type: START
                    name: Start
                  - id: ask
                    type: PUBLISH_EVENT
                    name: Ask
                    preconditionStepId: start
                    event: { destination: quotes, type: quote.requested }
                    awaitReply: { messageName: quote-ready, correlationKey: "${requestId}" }
                """);
        assertThatThrownBy(() -> validator.validateSource(incomplete, "ask.yaml"))
                .isInstanceOf(WorkflowDefinitionValidator.WorkflowDefinitionValidationException.class)
                .hasMessageContaining("correlationKey");
    }

    @Test
    void definitionWithSelfPreconditionFailsInvariantCheck() {
        Step selfPrecondition = new Step("s1", null, StepType.ACTION, "Step", null, "s1", null, null, false, "topic", null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
        var wd = new WorkflowDefinition("wd-1", "Test", 1, "desc",  false, 0, false, null, 0, List.of(selfPrecondition));

        assertThatThrownBy(() -> validator.validate(wd))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void definitionWithExecutionCapsPassesSchemaValidation() {
        Step capped = new Step("s1", null, StepType.ACTION, "Step s1", null, "start", null, null, false, "my-topic",
                null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 3, null);
        var wd = new WorkflowDefinition("wd-1", "Capped", 1, "desc",  false, 0, false, null, 100, List.of(startStep(), capped));

        assertThatNoException().isThrownBy(() -> validator.validate(wd));
    }

    @Test
    void definitionWithPreconditionCycleIsRejected() {
        Step s1 = new Step("s1", null, StepType.ACTION, "S1", null, "s2", null, null, false, "topic", null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
        Step s2 = new Step("s2", null, StepType.ACTION, "S2", null, "s1", null, null, false, "topic", null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
        var wd = new WorkflowDefinition("wd-1", "Cyclic", 1, "desc",  false, 0, false, null, 0, List.of(s1, s2));

        assertThatThrownBy(() -> validator.validate(wd))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void definitionWithMissingNameFailsSchemaValidation() {
        var wd = new WorkflowDefinition("wd-1", null, 1, "desc",  false, 0, false, null, 0, List.of(startStep(), actionStep("s1")));

        assertThatThrownBy(() -> validator.validate(wd))
                .isInstanceOf(WorkflowDefinitionValidator.WorkflowDefinitionValidationException.class);
    }
}
