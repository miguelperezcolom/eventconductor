package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.EventFileTrigger;
import io.mateu.workflow.domain.aggregates.EventTrigger;
import io.mateu.workflow.domain.aggregates.EventTriggerOrigin;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EventTriggerCatalogTest {

    private static WorkflowDefinition definition(String id, EventTrigger... triggers) {
        return new WorkflowDefinition(id, id, 1, null, false, 0, false, null, 0, List.of())
                .withTriggers(triggers.length == 0 ? null : List.of(triggers));
    }

    private static EventTrigger trigger(String id, String type) {
        return new EventTrigger(id, "bookings", type, null, null, null);
    }

    @Test
    void definitionTriggersComeFirstThenTheFileEntriesOfThatWorkflowBySourceAndIndex() {
        var booking = definition("booking", trigger(null, "com.acme.booking.created"));
        var other = definition("other");
        var stored = List.of(
                new EventFileTrigger("git://repo", 0, "other", trigger("from-git", "com.acme.other.*")),
                new EventFileTrigger("classpath:/workflows/events.yaml", 1, "booking", trigger(null, "com.acme.booking.cancelled")),
                new EventFileTrigger("classpath:/workflows/events.yaml", 0, "booking", trigger("cp", "com.acme.booking.*")));

        var catalog = EventTriggerCatalog.of(List.of(other, booking), stored);

        assertThat(catalog).extracting(e -> e.definition().id() + "/" + e.trigger().id() + " " + e.origin())
                .containsExactly(
                        "booking/com.acme.booking.created DEFINITION",
                        "booking/cp EVENTS_FILE",
                        "booking/com.acme.booking.cancelled EVENTS_FILE",
                        "other/from-git EVENTS_FILE");
        assertThat(catalog).allMatch(EventTriggerCatalog.Entry::fires);
        assertThat(catalog.get(1).sourceLabel()).isEqualTo("events.yaml (classpath)");
        assertThat(catalog.get(3).sourceLabel()).isEqualTo("events.yaml (git)");
        assertThat(catalog.get(0).sourceLabel()).isEqualTo("Definition");
        assertThat(catalog.get(0).declaredIn()).isEqualTo("definition booking triggers[0]");
        assertThat(catalog.get(2).declaredIn()).isEqualTo("events.yaml (classpath) classpath:/workflows/events.yaml entry 1");
    }

    @Test
    void aFileEntryWithoutAnIdGetsOneDerivedFromItsTypeUniqueWithinTheWorkflow() {
        var booking = definition("booking", trigger(null, "com.acme.booking.created"));
        var stored = List.of(
                new EventFileTrigger("/dir", 0, "booking", trigger(null, "com.acme.booking.created")),
                new EventFileTrigger("/dir", 1, "booking", trigger(null, "com.acme.booking.created")),
                // A declared id claims its name before any derived one, whatever the order.
                new EventFileTrigger("/dir", 2, "booking", trigger("com.acme.booking.created-3", "x")));

        var catalog = EventTriggerCatalog.of(List.of(booking), stored);

        assertThat(catalog).extracting(e -> e.trigger().id()).containsExactly(
                "com.acme.booking.created", "com.acme.booking.created-2", "com.acme.booking.created-4",
                "com.acme.booking.created-3");
        assertThat(catalog).allMatch(EventTriggerCatalog.Entry::fires);
    }

    @Test
    void aFileEntryDeclaringAnIdTheWorkflowAlreadyHasIsADuplicateThatNeverFires() {
        var booking = definition("booking", trigger("web", "com.acme.booking.created"));
        var stored = List.of(
                new EventFileTrigger("/dir", 0, "booking", trigger("web", "com.acme.booking.*")),
                new EventFileTrigger("/dir", 1, "booking", trigger("api", "com.acme.booking.*")),
                new EventFileTrigger("/other", 0, "booking", trigger("api", "com.acme.booking.*")));

        var catalog = EventTriggerCatalog.of(List.of(booking), stored);

        assertThat(catalog).extracting(e -> e.trigger().id() + ":" + e.fires()).containsExactly(
                "web:true", "web:false", "api:true", "api:false");
        assertThat(catalog.get(1).duplicateOf()).isEqualTo("definition booking triggers[0]");
        assertThat(catalog.get(3).duplicateOf()).isEqualTo("events.yaml (directory) /dir entry 1");
        // Keys list every declaration, duplicates included: their pauses are never orphans.
        assertThat(EventTriggerCatalog.keys(catalog)).containsExactlyInAnyOrder("booking/web", "booking/api");
    }

    @Test
    void engineCreatedTriggersComeLastAndAreHiddenOnceAFileDeclaresTheirKey() {
        var booking = definition("booking", trigger("web", "com.acme.booking.created"));
        var stored = List.of(
                new EventFileTrigger(EventFileTrigger.API_SOURCE, 0, "booking", trigger("web", "x")),      // taken over
                new EventFileTrigger(EventFileTrigger.API_SOURCE, 0, "booking", trigger("ui-only", "y")),
                new EventFileTrigger(EventFileTrigger.API_SOURCE, 0, "booking", trigger("api", "z")),      // taken over
                new EventFileTrigger("/dir", 0, "booking", trigger("api", "com.acme.booking.*")));

        var catalog = EventTriggerCatalog.of(List.of(booking), stored);

        assertThat(catalog).extracting(e -> e.trigger().id() + " " + e.origin() + " " + e.editable()).containsExactly(
                "web DEFINITION false", "api EVENTS_FILE false", "ui-only API true");
        assertThat(catalog.get(2).sourceLabel()).isEqualTo("UI/API");
        assertThat(catalog.get(2).declaredIn()).isEqualTo("UI/API");
        assertThat(EventTriggerCatalog.fileOwnedKeys(catalog)).containsExactlyInAnyOrder("booking/web", "booking/api");
        assertThat(EventTriggerCatalog.owner(catalog, "booking", "ui-only")).isPresent();
        assertThat(EventTriggerCatalog.owner(catalog, "booking", "nope")).isEmpty();
    }

    @Test
    void aFileEntryNamingAWorkflowTheEngineDoesNotHaveIsDropped() {
        var stored = List.of(new EventFileTrigger("/dir", 0, "ghost", trigger(null, "t")));

        assertThat(EventTriggerCatalog.of(List.of(definition("booking")), stored)).isEmpty();
    }

    @Test
    void theFileTriggerKeepsEverythingTheEntryDeclared() {
        var trigger = new EventTrigger(null, "cdc", "*", "data.op == 'u'", "c-${data.id}", Map.of("id", "${data.id}"));
        var catalog = EventTriggerCatalog.of(List.of(definition("sync")), List.of(new EventFileTrigger("/dir", 0, "sync", trigger)));

        assertThat(catalog).singleElement().satisfies(entry -> {
            assertThat(entry.trigger()).isEqualTo(trigger.withId("any"));
            assertThat(entry.origin()).isEqualTo(EventTriggerOrigin.EVENTS_FILE);
            assertThat(entry.key()).isEqualTo("sync/any");
        });
    }
}
