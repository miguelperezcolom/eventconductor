package io.mateu.workflow.application.services;

import io.mateu.workflow.domain.aggregates.EventFileTrigger;
import io.mateu.workflow.domain.aggregates.EventTrigger;
import io.mateu.workflow.domain.aggregates.TriggerPause;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.infra.out.memory.InMemoryEventTriggerRepository;
import io.mateu.workflow.infra.out.memory.InMemoryTriggerPauseRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Engine-created triggers and pauses follow their definition when it is saved or deleted. */
class DefinitionTriggerCleanupTest {

    final InMemoryEventTriggerRepository triggers = new InMemoryEventTriggerRepository();
    final InMemoryTriggerPauseRepository pauses = new InMemoryTriggerPauseRepository();
    final DefinitionTriggerCleanup cleanup = new DefinitionTriggerCleanup(triggers, pauses);

    static EventTrigger trigger(String id) {
        return new EventTrigger(id, "bookings", "com.acme.booking.*", null, null, null);
    }

    static WorkflowDefinition definition(String id) {
        return new WorkflowDefinition(id, "The " + id, 1, null, false, 0, false, null, 0, List.of());
    }

    @Test
    void deletingADefinitionDeletesItsEngineCreatedTriggersAndEveryPauseOfItsTriggers() {
        triggers.save(new EventFileTrigger(EventFileTrigger.API_SOURCE, 0, "booking", trigger("api")));
        triggers.save(new EventFileTrigger(EventFileTrigger.API_SOURCE, 0, "other", trigger("api")));
        triggers.replace("git:repo", List.of(new EventFileTrigger("git:repo", 0, "booking", trigger("file"))));
        pauses.save(new TriggerPause("booking/api", Instant.now(), "ops"));
        pauses.save(new TriggerPause("booking/file", Instant.now(), "ops"));
        pauses.save(new TriggerPause("booking-v2/api", Instant.now(), "ops"));
        pauses.save(new TriggerPause("other/api", Instant.now(), "ops"));

        cleanup.definitionsDeleted(List.of("booking"));

        // The file's entry stays: its file still declares it, and another source may bring the workflow back.
        assertThat(triggers.findAll()).extracting(t -> t.source() + " " + t.key())
                .containsExactlyInAnyOrder("ui other/api", "git:repo booking/file");
        assertThat(pauses.findAll().keySet()).containsExactlyInAnyOrder("booking-v2/api", "other/api");
    }

    @Test
    void savingADefinitionThatDeclaresATriggerReplacesTheEngineCreatedCopyAndKeepsItsPause() {
        triggers.save(new EventFileTrigger(EventFileTrigger.API_SOURCE, 0, "booking", trigger("web")));
        triggers.save(new EventFileTrigger(EventFileTrigger.API_SOURCE, 0, "booking", trigger("phone")));
        pauses.save(new TriggerPause("booking/web", Instant.now(), "ops"));

        cleanup.definitionSaved(definition("booking").withTriggers(List.of(trigger("web"))));

        assertThat(triggers.findAll()).extracting(EventFileTrigger::key).containsExactly("booking/phone");
        assertThat(pauses.findAll().keySet()).containsExactly("booking/web");
    }

    @Test
    void aDefinitionWithoutTriggersOrStoresIsLeftAlone() {
        triggers.save(new EventFileTrigger(EventFileTrigger.API_SOURCE, 0, "booking", trigger("web")));
        cleanup.definitionSaved(definition("booking"));
        new DefinitionTriggerCleanup(null, null).definitionsDeleted(List.of("booking"));
        new DefinitionTriggerCleanup(null, null).definitionSaved(definition("booking").withTriggers(List.of(trigger("web"))));
        cleanup.definitionsDeleted(List.of());
        assertThat(triggers.findAll()).hasSize(1);
    }
}
