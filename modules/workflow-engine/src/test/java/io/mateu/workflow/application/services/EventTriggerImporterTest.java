package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.EventFileTrigger;
import io.mateu.workflow.domain.aggregates.EventTrigger;
import io.mateu.workflow.domain.aggregates.TriggerPause;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.infra.out.memory.InMemoryEventTriggerRepository;
import io.mateu.workflow.infra.out.memory.InMemoryTriggerPauseRepository;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EventTriggerImporterTest {

    private final WorkflowDefinitionRepository definitions = mock(WorkflowDefinitionRepository.class);
    private final InMemoryEventTriggerRepository repository = new InMemoryEventTriggerRepository();
    private final InMemoryTriggerPauseRepository pauses = new InMemoryTriggerPauseRepository();
    private final EventTriggerImporter importer = new EventTriggerImporter(definitions, repository, pauses);

    private static byte[] yaml(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private void known(String... ids) {
        when(definitions.findById(anyString())).thenReturn(Optional.empty());
        var all = new java.util.ArrayList<WorkflowDefinition>();
        for (var id : ids) {
            var definition = new WorkflowDefinition(id, id, 1, null, false, 0, false, null, 0, List.of());
            when(definitions.findById(id)).thenReturn(Optional.of(definition));
            all.add(definition);
        }
        when(definitions.findAll()).thenReturn(all);
    }

    @Test
    void parsesABareListWithEveryField() {
        var parsed = EventTriggerImporter.parse("events.yaml", yaml("""
                - id: on-customer-changed
                  destination: customers-cdc
                  type: "*"
                  workflow: sync-customer
                  filter: "data.op == 'u'"
                  businessKey: "customer-${data.after.id}"
                  variables:
                    customerId: "${data.after.id}"
                    retries: 3
                    tags: [a, b]
                - destination: bookings
                  type: com.acme.booking.created
                  workflow: booking
                """));

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.unreadable()).isFalse();
        assertThat(parsed.entries()).containsExactly(
                new EventTriggerImporter.Entry("sync-customer", new EventTrigger("on-customer-changed", "customers-cdc", "*",
                        "data.op == 'u'", "customer-${data.after.id}",
                        Map.of("customerId", "${data.after.id}", "retries", 3, "tags", List.of("a", "b")))),
                new EventTriggerImporter.Entry("booking", new EventTrigger(null, "bookings", "com.acme.booking.created", null, null, null)));
        assertThat(parsed.entries().get(0).trigger().variables().keySet()).containsExactly("customerId", "retries", "tags");
    }

    @Test
    void parsesTheTriggersKeyFormAndAnEmptyFile() {
        var keyed = EventTriggerImporter.parse("events.yml", yaml("""
                triggers:
                  - destination: bookings
                    type: com.acme.booking.*
                    workflow: booking
                """));
        assertThat(keyed.errors()).isEmpty();
        assertThat(keyed.entries()).hasSize(1);

        var empty = EventTriggerImporter.parse("events.yaml", yaml(""));
        assertThat(empty.entries()).isEmpty();
        assertThat(empty.unreadable()).isFalse();
    }

    @Test
    void reportsEachBadEntryAndKeepsTheRest() {
        var parsed = EventTriggerImporter.parse("events.yaml", yaml("""
                - destination: bookings
                  type: com.acme.booking.created
                  workflow: booking
                - destination: "book ings"
                  type: t
                  workflow: booking
                - destination: bookings
                  workflow: booking
                - destination: bookings
                  type: t
                - id: "-bad"
                  destination: bookings
                  type: t
                  workflow: booking
                  varaibles: {}
                - destination: bookings
                  type: t
                  workflow: booking
                  variables: not-a-map
                - just a string
                """));

        assertThat(parsed.unreadable()).isFalse();
        assertThat(parsed.entries()).hasSize(1);
        assertThat(parsed.errors()).hasSize(7)
                .anySatisfy(e -> assertThat(e).contains("entry 1").contains("not a valid destination"))
                .anySatisfy(e -> assertThat(e).contains("entry 2").contains("a type is required"))
                .anySatisfy(e -> assertThat(e).contains("entry 3").contains("'workflow'"))
                .anySatisfy(e -> assertThat(e).contains("entry 4").contains("not a valid trigger id"))
                .anySatisfy(e -> assertThat(e).contains("entry 4").contains("unknown field 'varaibles'"))
                .anySatisfy(e -> assertThat(e).contains("entry 5").contains("'variables' must be a map"))
                .anySatisfy(e -> assertThat(e).contains("entry 6").contains("expected an object"));
    }

    @Test
    void aFileThatIsNotAListIsUnreadable() {
        assertThat(EventTriggerImporter.parse("events.yaml", yaml("triggers: 3")).unreadable()).isTrue();
        assertThat(EventTriggerImporter.parse("events.yaml", yaml("schedules: []")).unreadable()).isTrue();
        assertThat(EventTriggerImporter.parse("events.yaml", yaml("- [")).unreadable()).isTrue();
    }

    @Test
    void importReplacesTheSourceSkippingRepeatedIdsAndKeepingWorkflowsNotKnownYet() {
        known("booking");
        var files = List.of(new EventTriggerImporter.EventFile("events.yaml", yaml("""
                - id: web
                  destination: bookings
                  type: com.acme.booking.created
                  workflow: booking
                - id: web
                  destination: bookings
                  type: com.acme.booking.*
                  workflow: booking
                - destination: bookings
                  type: com.acme.booking.created
                  workflow: ghost
                - destination: payments
                  type: com.acme.payment.*
                  workflow: booking
                """)));

        var result = importer.importSource("/dir", files);

        assertThat(result.errors()).singleElement().asString().contains("trigger id 'web' is declared more than once");
        // A workflow not known yet — another source may import it later — is a warning, and the entry is kept.
        assertThat(result.warnings()).singleElement().asString()
                .contains("workflow 'ghost' not known yet — the trigger applies once it is imported");
        assertThat(result.triggers()).containsExactly(
                new EventFileTrigger("/dir", 0, "booking", new EventTrigger("web", "bookings", "com.acme.booking.created", null, null, null)),
                new EventFileTrigger("/dir", 1, "ghost", new EventTrigger(null, "bookings", "com.acme.booking.created", null, null, null)),
                new EventFileTrigger("/dir", 2, "booking", new EventTrigger(null, "payments", "com.acme.payment.*", null, null, null)));
        assertThat(repository.findAll()).isEqualTo(result.triggers());

        // A re-import replaces what the source said; an empty import clears it.
        importer.importSource("/dir", List.of(new EventTriggerImporter.EventFile("events.yaml", yaml("""
                - destination: bookings
                  type: com.acme.booking.cancelled
                  workflow: booking
                """))));
        assertThat(repository.findAll()).extracting(t -> t.trigger().type()).containsExactly("com.acme.booking.cancelled");
        importer.importSource("/dir", List.of());
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void anUnreadableFileLeavesThePreviousTriggersOfTheSourceInPlace() {
        known("booking");
        importer.importSource("/dir", List.of(new EventTriggerImporter.EventFile("events.yaml", yaml("""
                - destination: bookings
                  type: t
                  workflow: booking
                """))));

        var result = importer.importSource("/dir", List.of(new EventTriggerImporter.EventFile("events.yaml", yaml("nope: 1"))));

        assertThat(result.errors()).singleElement().asString().contains("expected a list of triggers");
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void reconcileDeletesThePausesOfTriggersThatNoLongerExist() {
        known("booking");
        pauses.save(new TriggerPause("booking/web", Instant.now(), "ops"));
        pauses.save(new TriggerPause("booking/gone", Instant.now(), "ops"));
        pauses.save(new TriggerPause("ghost/x", Instant.now(), "ops"));

        importer.importSource("/dir", List.of(new EventTriggerImporter.EventFile("events.yaml", yaml("""
                - id: web
                  destination: bookings
                  type: t
                  workflow: booking
                """))));

        assertThat(pauses.findAll().keySet()).containsExactly("booking/web");
    }

    @Test
    void anEntryForAWorkflowImportedLaterAppliesOnceItIs() {
        // The classpath events.yaml is imported first; the workflow it names comes from a git clone after.
        known("booking");
        importer.importSource("classpath:/workflows/events.yaml", List.of(new EventTriggerImporter.EventFile("events.yaml", yaml("""
                - id: on-signup
                  destination: signups
                  type: com.acme.signup
                  workflow: onboarding
                """))));
        assertThat(io.mateu.workflow.domain.services.EventTriggerCatalog.of(definitions.findAll(), repository.findAll())).isEmpty();

        known("booking", "onboarding");

        assertThat(io.mateu.workflow.domain.services.EventTriggerCatalog.of(definitions.findAll(), repository.findAll()))
                .singleElement().satisfies(e -> assertThat(e.key()).isEqualTo("onboarding/on-signup"));
    }
}
