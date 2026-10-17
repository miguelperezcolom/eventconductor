package io.mateu.workflow.infra.out.classpath;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.DefinitionFileFormat;
import io.mateu.workflow.application.services.EventTriggerImporter;
import io.mateu.workflow.domain.aggregates.EventFileTrigger;
import io.mateu.workflow.domain.aggregates.EventTrigger;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.infra.out.memory.InMemoryEventTriggerRepository;
import io.mateu.workflow.infra.out.memory.InMemoryTriggerPauseRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClasspathEventTriggerLoaderTest {

    private final WorkflowDefinitionRepository definitions = mock(WorkflowDefinitionRepository.class);
    private final InMemoryEventTriggerRepository triggers = new InMemoryEventTriggerRepository();
    private final ClasspathEventTriggerLoader loader =
            new ClasspathEventTriggerLoader(new EventTriggerImporter(definitions, triggers, new InMemoryTriggerPauseRepository()));

    {
        when(definitions.findById(anyString())).thenReturn(Optional.empty());
        when(definitions.findById("sync-customer")).thenReturn(Optional.of(
                new WorkflowDefinition("sync-customer", "Sync customer", 1, null, false, 0, false, null, 0, List.of())));
        when(definitions.findAll()).thenReturn(List.of());
    }

    @Test
    void loadsABareListKeepingEntriesForWorkflowsNotKnownYet() {
        var result = loader.load("classpath:/events-fixtures/listed/");

        // The classpath loads first: a workflow a git or directory import brings later is a warning, not a loss.
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).singleElement().asString().contains("workflow 'unknown-workflow' not known yet");
        assertThat(triggers.findAll()).containsExactly(new EventFileTrigger(ClasspathEventTriggerLoader.SOURCE, 0,
                "sync-customer", new EventTrigger("on-customer-changed", "customers-cdc", "*", "data.op == 'u'",
                        "customer-${data.after.id}", Map.of("customerId", "${data.after.id}", "email", "${data.after.email}"))),
                new EventFileTrigger(ClasspathEventTriggerLoader.SOURCE, 1, "unknown-workflow",
                        new EventTrigger(null, "bookings", "com.acme.booking.created", null, null, null)));
    }

    @Test
    void loadsTheTriggersKeyFormAndReplacesWhatThePreviousLoadLeft() {
        loader.load("classpath:/events-fixtures/listed/");

        loader.load("classpath:/events-fixtures/keyed/");

        assertThat(triggers.findAll()).extracting(t -> t.trigger().type()).containsExactly("com.acme.booking.*");
    }

    @Test
    void noFileClearsTheClasspathSource() {
        loader.load("classpath:/events-fixtures/listed/");

        var result = loader.load("classpath:/events-fixtures/none/");

        assertThat(result.errors()).isEmpty();
        assertThat(triggers.findAll()).isEmpty();
    }

    @Test
    void anEventsFileIsNeverTakenForAWorkflowDefinition() {
        assertThat(DefinitionFileFormat.isDefinitionFileName("workflows/events.yaml")).isFalse();
        assertThat(DefinitionFileFormat.isDefinitionFileName("events.yml")).isFalse();
        assertThat(DefinitionFileFormat.isDefinitionFileName("booking-events.yaml")).isTrue();
        assertThat(DefinitionFileFormat.isDefinitionFileName("events.json")).isTrue();
    }

    @Test
    void aDatabaseDownAtStartupDoesNotFailTheBootAndTheLoadIsRetried() throws Exception {
        var importer = mock(EventTriggerImporter.class);
        var stored = new java.util.concurrent.CountDownLatch(1);
        when(importer.importSource(anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenThrow(new IllegalStateException("Could not open JPA EntityManager for transaction"))
                .thenAnswer(invocation -> {
                    stored.countDown();
                    return new EventTriggerImporter.Result(List.of(), List.of());
                });
        var flaky = new ClasspathEventTriggerLoader(importer);

        flaky.loadOrRetryInBackground(10); // must not throw

        assertThat(stored.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        flaky.stop();
    }
}
