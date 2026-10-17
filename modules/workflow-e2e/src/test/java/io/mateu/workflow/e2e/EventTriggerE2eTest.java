package io.mateu.workflow.e2e;

import io.mateu.workflow.application.services.EventTriggerService;
import io.mateu.workflow.application.services.InboundEventService;
import io.mateu.workflow.domain.aggregates.EventReceipt;
import io.mateu.workflow.domain.aggregates.InboundEvent;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.domain.services.InboundEvents;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Domain events in, embedded mode: event triggers start processes, {@code onEvent} waits are completed
 * by an event, and the same event is never used twice by one receiver.
 */
class EventTriggerE2eTest extends AbstractE2eTest {

    @Autowired InboundEventService inbound;
    @Autowired EventTriggerService triggers;
    @Autowired ApplicationEventPublisher applicationEvents;

    private static InboundEvent booking(String id, String bookingId, String channel) {
        return new InboundEvent("e2e-bookings", id, "pms/e2e", "com.acme.booking.created", null, null, bookingId,
                "application/json", "{\"bookingId\":\"" + bookingId + "\",\"nights\":2,\"channel\":\"" + channel + "\"}", Map.of());
    }

    private static InboundEvent payment(String id, String bookingId, String paymentId, int amount) {
        return new InboundEvent("e2e-payments", id, "payments/e2e", "com.acme.payment.captured", null, null, null,
                "application/json", "{\"bookingId\":\"" + bookingId + "\",\"paymentId\":\"" + paymentId
                + "\",\"amount\":" + amount + "}", Map.of());
    }

    private static String key(InboundEvent event) {
        return InboundEvents.defaultBusinessKey("event-trigger", "com.acme.booking.created", event);
    }

    @Test
    void aTriggerStartsAProcessWithTheVariablesItMaps() {
        var event = booking("evt-1", "B-1", "phone");

        var result = inbound.receive(event);

        assertThat(result.started()).isEqualTo(1);
        var process = process(key(event));
        assertThat(process.getWorkflowDefinitionId()).isEqualTo("event-trigger");
        assertThat(process.getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(process.getVariables()).contains(new Variable("bookingId", "B-1"), new Variable("nights", "2"),
                new Variable("eventId", "evt-1"));
        // Not a 'web' booking: the filtered trigger did nothing, and the disabled definition never starts.
        assertThat(processRepository.findByBusinessKey("web-B-1")).isEmpty();
        assertThat(processRepository.findByBusinessKey(
                InboundEvents.defaultBusinessKey("event-trigger-disabled", "com.acme.booking.created", event))).isEmpty();
    }

    @Test
    void theSameEventDoesNotStartASecondProcess() {
        var event = booking("evt-2", "B-2", "web");

        var first = inbound.receive(event);
        var again = inbound.receive(event);

        // Two triggers matched the first time (the exact one and the filtered 'web' wildcard)…
        assertThat(first.started()).isEqualTo(2);
        assertThat(process("web-B-2").getVariables()).contains(new Variable("channel", "web"), new Variable("nights", "2"));
        // …and neither acts on the redelivery.
        assertThat(again.started()).isZero();
        assertThat(again.duplicates()).containsExactlyInAnyOrder(
                "trigger:event-trigger/com.acme.booking.created", "trigger:event-trigger/web");
        assertThat(processRepository.findAll().stream().filter(p -> "web-B-2".equals(p.getBusinessKey())).count()).isEqualTo(1);
    }

    @Test
    void anotherEventWithTheSameAuthorKeyDoesNotStartASecondProcessEither() {
        inbound.receive(booking("evt-3a", "B-3", "web"));
        var second = inbound.receive(booking("evt-3b", "B-3", "web"));

        // A new event: the default-keyed trigger starts again, the one keyed by booking collapses.
        assertThat(processRepository.findAll().stream().filter(p -> "web-B-3".equals(p.getBusinessKey())).count()).isEqualTo(1);
        assertThat(second.receipts()).extracting(EventReceipt::outcome).contains(EventReceipt.Outcome.STARTED);
    }

    @Test
    void aPausedTriggerStartsNothingAndDoesNotReplayOnResume() {
        triggers.pause("event-trigger", "web", "e2e");
        try {
            inbound.receive(booking("evt-4", "B-4", "web"));
            assertThat(processRepository.findByBusinessKey("web-B-4")).isEmpty();
            assertThat(triggers.get("event-trigger", "web").state()).isEqualTo(EventTriggerService.TriggerState.PAUSED);
        } finally {
            triggers.resume("event-trigger", "web");
        }
        assertThat(processRepository.findByBusinessKey("web-B-4")).isEmpty();
        inbound.receive(booking("evt-4b", "B-4", "web"));
        assertThat(processRepository.findByBusinessKey("web-B-4")).isPresent();
    }

    @Test
    void anEventPublishedAsASpringApplicationEventIsReceived() {
        var event = booking("evt-5", "B-5", "phone");
        applicationEvents.publishEvent(event);
        assertThat(processRepository.findByBusinessKey(key(event))).isPresent();
    }

    @Test
    void anEventWithoutIdOrSourceIsRefused() {
        assertThatThrownBy(() -> inbound.receive(new InboundEvent("e2e-bookings", null, "s", "t", null, null, null, null, "{}", Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPublishEventOfThisEngineStartsAnotherWorkflow() {
        createProcess("event-loopback-publisher", "loop-1", new io.mateu.workflow.dtos.Variable("bookingId", "L-1"),
                new io.mateu.workflow.dtos.Variable("nights", "3"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(processRepository.findByBusinessKey("listener-L-1")).isPresent());
        assertThat(process("listener-L-1").getVariables()).contains(new Variable("bookingId", "L-1"), new Variable("nights", "3"));
    }

    @Test
    void aWaitIsCompletedByAnEventForItsKeyAndTheSameEventIsNotCorrelatedTwice() {
        var businessKey = "wait-" + UUID.randomUUID();
        createProcess("event-wait", businessKey, new io.mateu.workflow.dtos.Variable("bookingId", "W-1"));
        assertThat(step(businessKey, "first-payment").getStatus()).isEqualTo(StepExecutionStatus.PENDING);

        // Another booking's payment, and a filtered-out one, leave it waiting.
        inbound.receive(payment("pay-0", "W-other", "P-0", 10));
        inbound.receive(payment("pay-00", "W-1", "P-00", 0));
        assertThat(step(businessKey, "first-payment").getStatus()).isEqualTo(StepExecutionStatus.PENDING);

        var delivered = inbound.receive(payment("pay-1", "W-1", "P-1", 10));
        assertThat(delivered.delivered()).isEqualTo(1);
        assertThat(step(businessKey, "first-payment").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(process(businessKey).getVariables()).contains(new Variable("lastPaymentId", "P-1"));
        assertThat(step(businessKey, "second-payment").getStatus()).isEqualTo(StepExecutionStatus.PENDING);

        // The same event again must not complete the second wait.
        var redelivered = inbound.receive(payment("pay-1", "W-1", "P-1", 10));
        assertThat(redelivered.delivered()).isZero();
        assertThat(redelivered.duplicates()).containsExactly("wait:e2e-payment-captured/W-1");
        assertThat(step(businessKey, "second-payment").getStatus()).isEqualTo(StepExecutionStatus.PENDING);

        inbound.receive(payment("pay-2", "W-1", "P-2", 5));
        assertThat(process(businessKey).getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(process(businessKey).getVariables()).contains(new Variable("lastPaymentId", "P-2"));
        assertThat(triggers.received(50, "event-wait")).extracting(EventReceipt::eventId).contains("pay-1", "pay-2");
    }

    private static InboundEvent cdc(String id, String op, String customerId, String email) {
        // A Debezium-style record: no CloudEvents type, the change envelope as the payload.
        return new InboundEvent("e2e-customers-cdc", id, "kafka://e2e.public.customers", null, null, null, customerId,
                "application/json", "{\"op\":\"" + op + "\",\"after\":{\"id\":\"" + customerId + "\",\"email\":\"" + email + "\"}}",
                Map.of());
    }

    @Test
    void aTriggerDeclaredInEventsYamlStartsAProcessLikeOneDeclaredInTheDefinition() {
        var result = inbound.receive(cdc("cdc-1", "u", "C-1", "c1@acme.com"));

        assertThat(result.started()).isEqualTo(1);
        assertThat(result.receipts()).singleElement().satisfies(receipt -> {
            assertThat(receipt.workflowId()).isEqualTo("event-file-trigger");
            assertThat(receipt.triggerId()).isEqualTo("on-customer-updated");
        });
        var process = process("customer-C-1");
        assertThat(process.getWorkflowDefinitionId()).isEqualTo("event-file-trigger");
        assertThat(process.getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(process.getVariables()).contains(new Variable("customerId", "C-1"), new Variable("email", "c1@acme.com"));

        // The filter holds only for updates; a create starts nothing and leaves no receipt.
        var create = inbound.receive(cdc("cdc-2", "c", "C-2", "c2@acme.com"));
        assertThat(create.started()).isZero();
        assertThat(create.receipts()).isEmpty();
    }

    @Test
    void anEventsYamlTriggerIsListedWithItsSourceAndOperatedByTheSameKey() {
        var view = triggers.get("event-file-trigger", "on-customer-updated");

        assertThat(view.origin()).isEqualTo("EVENTS_FILE");
        assertThat(view.sourceLabel()).isEqualTo("events.yaml (classpath)");
        assertThat(view.source()).isEqualTo("classpath:/workflows/events.yaml");
        assertThat(view.state()).isEqualTo(EventTriggerService.TriggerState.ACTIVE);
        assertThat(triggers.listenedDestinations()).contains("e2e-customers-cdc");

        triggers.pause("event-file-trigger", "on-customer-updated", "ops");
        assertThat(inbound.receive(cdc("cdc-3", "u", "C-3", "c3@acme.com")).started()).isZero();
        triggers.resume("event-file-trigger", "on-customer-updated");
        assertThat(inbound.receive(cdc("cdc-4", "u", "C-4", "c4@acme.com")).started()).isEqualTo(1);
    }

    @Test
    void aTriggerCreatedThroughTheEngineStartsAProcessAndIsPromotedToYaml() {
        var created = triggers.create(new EventTriggerService.TriggerDraft("api-cdc", "event-file-trigger", "e2e-customers-cdc",
                "*", "data.op == 'c'", "created-${data.after.id}", Map.of("customerId", "${data.after.id}")));
        assertThat(created.origin()).isEqualTo("API");
        assertThat(created.editable()).isTrue();

        var result = inbound.receive(cdc("api-1", "c", "C-21", "c21@acme.com"));

        assertThat(result.started()).isEqualTo(1);
        assertThat(process("created-C-21").getVariables()).contains(new Variable("customerId", "C-21"));
        assertThat(triggers.exportYaml("event-file-trigger", "api-cdc")).contains("- id: api-cdc", "workflow: event-file-trigger");

        triggers.delete("event-file-trigger", "api-cdc");
        assertThat(inbound.receive(cdc("api-2", "c", "C-22", "c22@acme.com")).started()).isZero();
    }
}
