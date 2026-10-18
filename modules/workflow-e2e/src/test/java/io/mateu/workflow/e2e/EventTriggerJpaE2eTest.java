package io.mateu.workflow.e2e;

import io.mateu.workflow.application.out.EventReceiptRepository;
import io.mateu.workflow.application.out.EventTriggerRepository;
import io.mateu.workflow.application.out.TriggerPauseRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.EventTriggerService;
import io.mateu.workflow.application.services.InboundEventService;
import io.mateu.workflow.domain.aggregates.EventReceipt;
import io.mateu.workflow.domain.aggregates.EventTrigger;
import io.mateu.workflow.domain.aggregates.InboundEvent;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.services.InboundEvents;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.infra.out.persistence.JpaEventReceiptRepository;
import io.mateu.workflow.infra.out.persistence.JpaTriggerPauseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain events in, on the durable path: a definition's triggers round-trip through its row, the
 * receipts that make a redelivery a duplicate live in {@code inbound_event_receipt}, and a trigger's
 * pause in {@code event_trigger_pause}.
 */
class EventTriggerJpaE2eTest extends AbstractJpaE2eTest {

    @Autowired WorkflowDefinitionRepository workflowDefinitionRepository;
    @Autowired EventReceiptRepository receipts;
    @Autowired TriggerPauseRepository pauses;
    @Autowired EventTriggerRepository fileTriggers;
    @Autowired InboundEventService inbound;
    @Autowired EventTriggerService triggers;

    private static InboundEvent booking(String id, String bookingId) {
        return new InboundEvent("e2e-bookings", id, "pms/e2e", "com.acme.booking.created", null, null, bookingId,
                "application/json", "{\"bookingId\":\"" + bookingId + "\",\"nights\":2,\"channel\":\"web\"}", Map.of());
    }

    @Test
    void triggersAreStoredAndAnEventStartsItsProcessOnce() {
        assertThat(receipts).isInstanceOf(JpaEventReceiptRepository.class);
        assertThat(workflowDefinitionRepository.findById("event-trigger").orElseThrow().triggers())
                .extracting(EventTrigger::id).containsExactly(null, "web");

        var event = booking("jpa-1", "J-1");
        assertThat(inbound.receive(event).started()).isEqualTo(2);
        var key = InboundEvents.defaultBusinessKey("event-trigger", "com.acme.booking.created", event);
        awaitStatus(key, ProcessStatus.COMPLETED);
        awaitStatus("web-J-1", ProcessStatus.COMPLETED);

        var again = inbound.receive(event);
        assertThat(again.started()).isZero();
        assertThat(again.duplicates()).hasSize(2);
        assertThat(triggers.received(10, "event-trigger")).extracting(EventReceipt::outcome)
                .containsOnly(EventReceipt.Outcome.STARTED).hasSize(2);
        // Past their retention they are forgotten.
        assertThat(receipts.purgeReceivedBefore(Instant.now().plusSeconds(60))).isEqualTo(2);
    }

    @Test
    void aPauseIsKeptInItsTable() {
        assertThat(pauses).isInstanceOf(JpaTriggerPauseRepository.class);
        triggers.pause("event-trigger", "web", "ops");
        assertThat(triggers.get("event-trigger", "web").pausedBy()).isEqualTo("ops");
        assertThat(inbound.receive(booking("jpa-2", "J-2")).started()).isEqualTo(1);
        triggers.resume("event-trigger", "web");
        assertThat(pauses.findAll()).isEmpty();
    }

    @Test
    void theClasspathEventsYamlIsStoredInEventTriggerAndItsTriggerStartsAProcess() {
        assertThat(fileTriggers).isInstanceOf(io.mateu.workflow.infra.out.persistence.JpaEventTriggerRepository.class);
        assertThat(fileTriggers.findAll()).singleElement().satisfies(stored -> {
            assertThat(stored.source()).isEqualTo("classpath:/workflows/events.yaml");
            assertThat(stored.workflowId()).isEqualTo("event-file-trigger");
            assertThat(stored.trigger().id()).isEqualTo("on-customer-updated");
            assertThat(stored.trigger().variables()).containsEntry("customerId", "${data.after.id}");
        });

        var result = inbound.receive(new InboundEvent("e2e-customers-cdc", "cdc-jpa-1", "kafka://e2e.public.customers",
                null, null, null, "C-9", "application/json",
                "{\"op\":\"u\",\"after\":{\"id\":\"C-9\",\"email\":\"c9@acme.com\"}}", Map.of()));

        assertThat(result.started()).isEqualTo(1);
        assertThat(triggers.get("event-file-trigger", "on-customer-updated").sourceLabel()).isEqualTo("events.yaml (classpath)");
    }

    @Test
    void aTriggerCreatedThroughTheEngineIsStoredUnderTheApiSourceUntilAFileTakesItOver() {
        triggers.create(new EventTriggerService.TriggerDraft("api-cdc", "event-file-trigger", "e2e-customers-cdc", "*",
                null, null, Map.of()));

        assertThat(fileTriggers.findAll()).extracting(t -> t.source() + " " + t.key()).containsExactlyInAnyOrder(
                "classpath:/workflows/events.yaml event-file-trigger/on-customer-updated",
                "ui event-file-trigger/api-cdc");
        assertThat(triggers.get("event-file-trigger", "api-cdc").sourceLabel()).isEqualTo("UI/API");

        triggers.update("event-file-trigger", "api-cdc", new EventTriggerService.TriggerDraft(null, null, "e2e-customers-cdc",
                "io.debezium.*", null, null, null));
        assertThat(triggers.get("event-file-trigger", "api-cdc").type()).isEqualTo("io.debezium.*");
        assertThat(fileTriggers.findAll()).hasSize(2);

        triggers.delete("event-file-trigger", "api-cdc");
        assertThat(fileTriggers.findAll()).hasSize(1);
    }

    @Test
    void anEventThatArrivedBeforeItsWaitIsCorrelatedOnRedeliveryAndOnlyThenDeduplicated() {
        var payment = new InboundEvent("e2e-payments", "jpa-pay-early", "payments/e2e", "com.acme.payment.captured",
                null, null, null, "application/json",
                "{\"bookingId\":\"JW-1\",\"paymentId\":\"JP-1\",\"amount\":10}", Map.of());
        inbound.receive(payment);

        var businessKey = "jpa-wait-" + java.util.UUID.randomUUID();
        createProcess("event-wait", businessKey, new io.mateu.workflow.dtos.Variable("bookingId", "JW-1"));
        org.awaitility.Awaitility.await().atMost(TIMEOUT).until(() -> steps(businessKey).stream().anyMatch(se ->
                "first-payment".equals(se.getStepId())
                        && se.getStatus() == io.mateu.workflow.domain.aggregates.StepExecutionStatus.PENDING));

        assertThat(inbound.receive(payment).duplicates()).isEmpty();
        org.awaitility.Awaitility.await().atMost(TIMEOUT).until(() -> step(businessKey, "first-payment").getStatus()
                == io.mateu.workflow.domain.aggregates.StepExecutionStatus.COMPLETED);
        org.awaitility.Awaitility.await().atMost(TIMEOUT).until(() -> receipts.recent(50, "event-wait").stream()
                .anyMatch(r -> "jpa-pay-early".equals(r.eventId())));

        assertThat(inbound.receive(payment).duplicates()).containsExactly("wait:e2e-payment-captured/JW-1");
    }

    @Test
    void deletingADefinitionTakesItsEngineCreatedTriggersAndPausesWithIt() {
        triggers.create(new EventTriggerService.TriggerDraft("api-made", "event-trigger", "e2e-bookings",
                "com.acme.booking.cancelled", null, null, null));
        triggers.pause("event-trigger", "api-made", "ops");
        triggers.pause("event-trigger", "web", "ops");
        assertThat(fileTriggers.findAll()).anySatisfy(t -> assertThat(t.key()).isEqualTo("event-trigger/api-made"));

        var definition = workflowDefinitionRepository.findById("event-trigger").orElseThrow();
        workflowDefinitionRepository.save(definition.withRuntimeStatus(io.mateu.workflow.domain.aggregates.WorkflowStatus.DISABLED));
        workflowDefinitionRepository.deleteAllById(java.util.List.of("event-trigger"));

        // Re-creating a definition with the same id must not bring them back, paused or not.
        assertThat(fileTriggers.findAll()).noneSatisfy(t -> assertThat(t.key()).isEqualTo("event-trigger/api-made"));
        assertThat(pauses.findAll()).isEmpty();
        workflowDefinitionRepository.save(definition);
        assertThat(triggers.list("event-trigger", null)).extracting(EventTriggerService.TriggerView::triggerId)
                .doesNotContain("api-made");
    }

    @Test
    void importProvenanceIsKeptInTheDatabase(@Autowired io.mateu.workflow.webhook.ImportedDefinitionsRegistry registry) {
        assertThat(registry).isInstanceOf(io.mateu.workflow.infra.out.persistence.JpaImportedDefinitionsRegistry.class);
    }
}
