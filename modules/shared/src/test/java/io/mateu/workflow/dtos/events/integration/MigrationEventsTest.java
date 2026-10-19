package io.mateu.workflow.dtos.events.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.domain.ProcessMigrated;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationEventsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aMigrationRequestTravelsAsADomainEventKeyedByItsProcess() throws Exception {
        var request = new MigrateProcessRequested("p-1", 3, Map.of("b", "b2"), null, "fix", "bob");
        assertThat(request.partitionKey()).isEqualTo("p-1");
        assertThat(request.variableMapping()).isEmpty();
        assertThat(new MigrateProcessRequested("p-1", null, null, null, "r", null).stepMapping()).isEmpty();

        var json = mapper.writeValueAsString((DomainEvent) request);
        assertThat(json).contains("migrate-process-requested");
        assertThat(mapper.readValue(json, DomainEvent.class)).isEqualTo(request);
    }

    @Test
    void theMigratedEventToo() throws Exception {
        var event = new ProcessMigrated("p-1", "order", 2, 3, Map.of("a", "a"), "bob", LocalDateTime.of(2026, 10, 19, 10, 0));
        assertThat(event.partitionKey()).isEqualTo("p-1");
        assertThat(event.occurredAt()).isEqualTo(LocalDateTime.of(2026, 10, 19, 10, 0));
        var undated = new ProcessMigrated("p-1", "order", 2, 3, Map.of("a", "a"), "bob", null);
        var json = mapper.writeValueAsString((DomainEvent) undated);
        assertThat(json).contains("process-migrated");
        assertThat(mapper.readValue(json, DomainEvent.class)).isEqualTo(undated);
    }
}
