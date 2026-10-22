package io.mateu.workflow.infra.out.persistence;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.application.out.ProcessSnapshotReader;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import org.springframework.stereotype.Component;

@Component
public class JsonProcessSnapshotReader implements ProcessSnapshotReader {
    @Override
    public WorkflowDefinition read(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) throw new IllegalStateException("Process definition snapshot is unavailable.");
        return JsonSerializer.pojoFromJson(snapshot, WorkflowDefinition.class);
    }
}
