package io.mateu.workflow.infra.in.ui.pages.events;

import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.data.SearchRequest;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.EventTriggerService;
import io.mateu.workflow.application.services.EventTriggerService.TriggerDraft;
import io.mateu.workflow.application.services.EventTriggerService.TriggerState;
import io.mateu.workflow.domain.aggregates.EventTrigger;
import io.mateu.workflow.domain.aggregates.EventTriggerOrigin;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.infra.config.EventDestinationsProperties;
import io.mateu.workflow.infra.out.memory.InMemoryEventReceiptRepository;
import io.mateu.workflow.infra.out.memory.InMemoryEventTriggerRepository;
import io.mateu.workflow.infra.out.memory.InMemoryTriggerPauseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The Event triggers page and a trigger's page, over the real service and in-memory stores. */
class EventTriggersTest {

    final WorkflowDefinitionRepository definitions = mock(WorkflowDefinitionRepository.class);
    EventTriggerService service;
    EventTriggers page;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        var booking = new WorkflowDefinition("booking", "Booking", 1, null, false, 0, false, null, 0, List.of())
                .withTriggers(List.of(new EventTrigger("web", "bookings", "com.acme.booking.*", null, null, null)));
        var sync = new WorkflowDefinition("sync-customer", "Sync customer", 1, null, false, 0, false, null, 0, List.of());
        when(definitions.findAll()).thenReturn(List.of(booking, sync));
        when(definitions.findById(anyString())).thenReturn(Optional.empty());
        when(definitions.findById("booking")).thenReturn(Optional.of(booking));
        when(definitions.findById("sync-customer")).thenReturn(Optional.of(sync));
        service = new EventTriggerService(definitions, new InMemoryTriggerPauseRepository(), new InMemoryEventReceiptRepository(),
                new EventDestinationsProperties(), new InMemoryEventTriggerRepository());
        ObjectProvider<EventTriggerDetailView> details = mock(ObjectProvider.class);
        when(details.getObject()).thenAnswer(i -> new EventTriggerDetailView(service, null));
        page = new EventTriggers(service, details, null);
    }

    private List<EventTriggerRow> search(String text, EventTriggerFilters filters, int pageNumber, int size) {
        return page.search(new SearchRequest(text, filters, null, new Pageable(pageNumber, size, List.of())), null)
                .page().content();
    }

    @Test
    void listsEveryTriggerWithItsSourceFilteredByOriginAndState() {
        service.create(new TriggerDraft("cdc", "sync-customer", "customers-cdc", "*", null, null, null));
        service.pause("sync-customer", "cdc", "ops");

        assertThat(search(null, null, 0, 10)).extracting(EventTriggerRow::id, EventTriggerRow::source)
                .containsExactly(tuple("booking~web", "Definition"), tuple("sync-customer~cdc", "UI/API"));
        assertThat(search(null, new EventTriggerFilters(null, EventTriggerOrigin.API, null), 0, 10))
                .extracting(EventTriggerRow::trigger).containsExactly("cdc");
        assertThat(search(null, new EventTriggerFilters(null, null, TriggerState.PAUSED), 0, 10))
                .extracting(EventTriggerRow::trigger).containsExactly("cdc");
        assertThat(search("UI/API", null, 0, 10)).extracting(EventTriggerRow::trigger).containsExactly("cdc");
        assertThat(search(null, null, 1, 1)).extracting(EventTriggerRow::trigger).containsExactly("cdc");
    }

    @Test
    void theDetailOffersEditAndDeleteOnlyForWhatWasCreatedHere() {
        service.create(new TriggerDraft("cdc", "sync-customer", "customers-cdc", "*", "data.op == 'u'", null, null));

        var created = (EventTriggerDetailView) page.view("sync-customer~cdc", null);
        assertThat(created.editable).isTrue();
        assertThat(created.isHidden("edit", null)).isFalse();
        assertThat(created.isHidden("deleteTrigger", null)).isFalse();
        assertThat(created.isHidden("pause", null)).isFalse();
        assertThat(created.isHidden("resume", null)).isTrue();
        assertThat(created.ownership).startsWith("Created here");
        assertThat(created.details.content()).contains("- id: cdc", "workflow: sync-customer");

        var declared = (EventTriggerDetailView) page.view("booking~web", null);
        assertThat(declared.isHidden("edit", null)).isTrue();
        assertThat(declared.isHidden("deleteTrigger", null)).isTrue();
        assertThat(declared.source).isEqualTo("definition booking triggers[0]");
    }

    @Test
    void theFormRoundTripsATriggerAndItsVariables() {
        service.create(new TriggerDraft("cdc", "sync-customer", "customers-cdc", "*", null, "c-${data.id}",
                java.util.Map.of("customerId", "${data.after.id}")));

        var form = page.edit("sync-customer~cdc", null);
        assertThat(form.id).isEqualTo("cdc");
        assertThat(form.workflowId).isEqualTo("sync-customer");
        assertThat(form.businessKey).isEqualTo("c-${data.id}");
        assertThat(form.variables).containsExactly(new Variable("customerId", "${data.after.id}"));
        assertThat(form.draft().variables()).containsEntry("customerId", "${data.after.id}");

        var empty = new EventTriggerForm();
        assertThat(empty.draft().variables()).isNull();
        assertThat(EventTriggerRow.workflowOf("a.b~c-d")).isEqualTo("a.b");
        assertThat(EventTriggerRow.triggerOf("a.b~c-d")).isEqualTo("c-d");
    }

    @Test
    void theFormKeepsNoVariablesApartFromDataMembers() {
        // null: data's top-level members. {}: none. The form must not turn one into the other.
        service.create(new TriggerDraft("none", "sync-customer", "customers-cdc", "*", null, null, java.util.Map.of()));
        service.create(new TriggerDraft("members", "sync-customer", "customers-cdc", "*", null, null, null));

        var none = page.edit("sync-customer~none", null);
        assertThat(none.dataMembers).isFalse();
        assertThat(none.draft().variables()).isNotNull().isEmpty();

        var members = page.edit("sync-customer~members", null);
        assertThat(members.dataMembers).isTrue();
        assertThat(members.draft().variables()).isNull();

        var fresh = new EventTriggerForm();
        fresh.dataMembers = false;
        assertThat(fresh.draft().variables()).isNotNull().isEmpty();
    }

    @Test
    void theFormRoundTripsNonTextTemplatesAsJson() {
        var nested = new java.util.LinkedHashMap<String, Object>();
        nested.put("id", "${data.id}");
        nested.put("tags", List.of("a", "b"));
        var variables = new java.util.LinkedHashMap<String, Object>();
        variables.put("customer", nested);
        variables.put("retries", 3);
        service.create(new TriggerDraft("nested", "sync-customer", "customers-cdc", "*", null, null, variables));

        var form = page.edit("sync-customer~nested", null);
        assertThat(form.variables).containsExactly(
                new Variable("customer", "{\"id\":\"${data.id}\",\"tags\":[\"a\",\"b\"]}"),
                new Variable("retries", "3"));
        // Saved back unchanged: the nested map is a map again, not its toString().
        assertThat(form.draft().variables()).containsEntry("customer", nested);
    }
}
