package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.EventTriggerService.InvalidTriggerException;
import io.mateu.workflow.application.services.EventTriggerService.TriggerDraft;
import io.mateu.workflow.application.services.EventTriggerService.TriggerNotEditableException;
import io.mateu.workflow.application.services.EventTriggerService.TriggerState;
import io.mateu.workflow.application.services.EventTriggerService.UnknownTriggerException;
import io.mateu.workflow.domain.aggregates.EventFileTrigger;
import io.mateu.workflow.domain.aggregates.EventTrigger;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.aggregates.WorkflowStatus;
import io.mateu.workflow.infra.config.EventDestinationsProperties;
import io.mateu.workflow.infra.out.memory.InMemoryEventReceiptRepository;
import io.mateu.workflow.infra.out.memory.InMemoryEventTriggerRepository;
import io.mateu.workflow.infra.out.memory.InMemoryTriggerPauseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Triggers created, edited, deleted and promoted through the engine, next to the ones files declare. */
class EventTriggerServiceTest {

    final WorkflowDefinitionRepository definitions = mock(WorkflowDefinitionRepository.class);
    final InMemoryEventTriggerRepository triggers = new InMemoryEventTriggerRepository();
    final InMemoryTriggerPauseRepository pauses = new InMemoryTriggerPauseRepository();
    final EventTriggerService service = new EventTriggerService(definitions, pauses, new InMemoryEventReceiptRepository(),
            new EventDestinationsProperties(), triggers);
    final EventTriggerImporter importer = new EventTriggerImporter(definitions, triggers, pauses);

    static WorkflowDefinition definition(String id) {
        return new WorkflowDefinition(id, "The " + id, 1, null, false, 0, false, null, 0, List.of());
    }

    @BeforeEach
    void someWorkflows() {
        var all = List.of(
                definition("sync-customer"),
                definition("booking").withTriggers(List.of(new EventTrigger("web", "bookings", "com.acme.booking.*",
                        "data.channel == 'web'", null, null))),
                definition("retired").withRuntimeStatus(WorkflowStatus.DISABLED));
        when(definitions.findAll()).thenReturn(all);
        when(definitions.findById(anyString())).thenReturn(Optional.empty());
        all.forEach(d -> when(definitions.findById(d.id())).thenReturn(Optional.of(d)));
    }

    @Test
    void createsATriggerOwnedByTheEngine() {
        var view = service.create(new TriggerDraft(" on-customer-updated ", "sync-customer", "customers-cdc", "*",
                "data.op == 'u'", "customer-${data.after.id}", Map.of("customerId", "${data.after.id}")));

        assertThat(view.triggerId()).isEqualTo("on-customer-updated");
        assertThat(view.origin()).isEqualTo("API");
        assertThat(view.sourceLabel()).isEqualTo("UI/API");
        assertThat(view.editable()).isTrue();
        assertThat(view.state()).isEqualTo(TriggerState.ACTIVE);
        assertThat(triggers.findAll()).singleElement().satisfies(stored -> {
            assertThat(stored.source()).isEqualTo(EventFileTrigger.API_SOURCE);
            assertThat(stored.key()).isEqualTo("sync-customer/on-customer-updated");
        });
        // Listed after the definition's own triggers, and listened to.
        assertThat(service.list(null, null)).extracting(v -> v.workflowId() + "/" + v.triggerId() + " " + v.origin())
                .containsExactly("booking/web DEFINITION", "sync-customer/on-customer-updated API");
        assertThat(service.listenedDestinations()).contains("customers-cdc", "bookings");
    }

    @Test
    void theDestinationOfAnAwaitedReplyEventIsListenedTo() {
        var ask = io.mateu.core.infra.JsonSerializer.pojoFromJson("""
                {"id": "ask", "type": "PUBLISH_EVENT", "name": "Ask",
                 "event": {"destination": "quote-requests", "type": "quote.requested"},
                 "awaitReply": {"messageName": "quote-ready", "correlationExpression": "businessKey",
                                "onEvent": {"destination": "quote-replies", "type": "quote.ready"}}}
                """, io.mateu.workflow.domain.aggregates.Step.class);
        when(definitions.findAll()).thenReturn(List.of(definition("quote").withSteps(List.of(ask))));

        assertThat(service.listenedDestinations()).containsExactly("quote-replies");
    }

    @Test
    void aDraftIsCheckedBeforeAnythingIsStored() {
        assertThatThrownBy(() -> service.create(new TriggerDraft(null, "sync-customer", "cdc", "*", null, null, null)))
                .isInstanceOf(InvalidTriggerException.class).hasMessageContaining("needs an id");
        assertThatThrownBy(() -> service.create(new TriggerDraft("x", "ghost", "cdc", "*", null, null, null)))
                .isInstanceOf(InvalidTriggerException.class).hasMessageContaining("unknown workflow 'ghost'");
        assertThatThrownBy(() -> service.create(new TriggerDraft("x", "sync-customer", "bad name", null, null, null, null)))
                .isInstanceOf(InvalidTriggerException.class)
                .satisfies(e -> assertThat(((InvalidTriggerException) e).violations()).hasSize(2));
        // An id a file already declares for that workflow is taken.
        assertThatThrownBy(() -> service.create(new TriggerDraft("web", "booking", "bookings", "t", null, null, null)))
                .isInstanceOf(InvalidTriggerException.class)
                .hasMessageContaining("already used by the trigger declared in definition booking triggers[0]");
        // The template check a definition's triggers get: a filter that does not parse is refused here,
        // not discovered as an ERROR receipt on every event.
        assertThatThrownBy(() -> service.create(new TriggerDraft("x", "sync-customer", "cdc", "*", "data.op ==", null, null)))
                .isInstanceOf(InvalidTriggerException.class).hasMessageContaining("filter");
        assertThatThrownBy(() -> service.create(new TriggerDraft("x", "sync-customer", "cdc", "*", null, "k-${data.id +}", null)))
                .isInstanceOf(InvalidTriggerException.class);
        assertThatThrownBy(() -> service.create(new TriggerDraft("x", "sync-customer", "cdc", "*", null, null,
                Map.of("v", "${(}"))))
                .isInstanceOf(InvalidTriggerException.class).hasMessageContaining("variables");
        assertThat(triggers.findAll()).isEmpty();
    }

    @Test
    void editsAndDeletesOnlyWhatWasCreatedHere() {
        service.create(new TriggerDraft("cdc", "sync-customer", "customers-cdc", "*", null, null, null));

        var edited = service.update("sync-customer", "cdc", new TriggerDraft(null, null, "customers-cdc",
                "io.debezium.*", "data.op == 'u'", null, Map.of()));
        assertThat(edited.type()).isEqualTo("io.debezium.*");
        assertThat(edited.filter()).isEqualTo("data.op == 'u'");
        assertThat(edited.variables()).isEmpty();
        assertThat(triggers.findAll()).hasSize(1);

        assertThatThrownBy(() -> service.update("sync-customer", "cdc", new TriggerDraft(null, "booking", "x", "t", null, null, null)))
                .isInstanceOf(InvalidTriggerException.class).hasMessageContaining("workflow never changes");
        assertThatThrownBy(() -> service.update("booking", "web", new TriggerDraft(null, null, "x", "t", null, null, null)))
                .isInstanceOf(TriggerNotEditableException.class).hasMessageContaining("definition booking triggers[0]");
        assertThatThrownBy(() -> service.delete("booking", "web")).isInstanceOf(TriggerNotEditableException.class);
        assertThatThrownBy(() -> service.delete("booking", "nope")).isInstanceOf(UnknownTriggerException.class);

        service.pause("sync-customer", "cdc", "ops");
        service.delete("sync-customer", "cdc");
        assertThat(triggers.findAll()).isEmpty();
        assertThat(pauses.findAll()).isEmpty();
        assertThatThrownBy(() -> service.get("sync-customer", "cdc")).isInstanceOf(UnknownTriggerException.class);
    }

    @Test
    void exportsTheEventsYamlEntryOfAnyTrigger() {
        service.create(new TriggerDraft("cdc", "sync-customer", "customers-cdc", "*", "data.op == 'u'",
                "customer-${data.after.id}", Map.of("customerId", "${data.after.id}")));

        assertThat(service.exportYaml("sync-customer", "cdc")).isEqualTo("""
                # events.yaml next to the workflow definitions. Once imported, this entry owns trigger 'cdc' of workflow 'sync-customer'.
                - id: cdc
                  destination: customers-cdc
                  type: "*"
                  workflow: sync-customer
                  filter: "data.op == 'u'"
                  businessKey: "customer-${data.after.id}"
                  variables:
                    customerId: "${data.after.id}"
                """);
        assertThat(service.exportYaml("booking", "web")).contains("- id: web", "workflow: booking")
                .doesNotContain("variables");
    }

    @Test
    void aFileDeclaringTheSameKeyTakesTheTriggerOverOnImportKeepingItsPause() {
        service.create(new TriggerDraft("cdc", "sync-customer", "customers-cdc", "*", null, null, null));
        service.pause("sync-customer", "cdc", "ops");

        importer.importSource("/dir", List.of(new EventTriggerImporter.EventFile("events.yaml", """
                - id: cdc
                  destination: customers-cdc
                  type: io.debezium.*
                  workflow: sync-customer
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8))));

        var view = service.get("sync-customer", "cdc");
        assertThat(view.origin()).isEqualTo("EVENTS_FILE");
        assertThat(view.type()).isEqualTo("io.debezium.*");
        assertThat(view.paused()).isTrue();
        assertThat(view.editable()).isFalse();
        assertThat(triggers.findAll()).extracting(EventFileTrigger::source).containsExactly("/dir");

        // Gone from the file, gone — with its pause.
        importer.importSource("/dir", List.of());
        assertThatThrownBy(() -> service.get("sync-customer", "cdc")).isInstanceOf(UnknownTriggerException.class);
        assertThat(pauses.findAll()).isEmpty();
    }
}
