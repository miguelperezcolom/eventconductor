package io.mateu.workflow.application.out;

import io.mateu.workflow.domain.aggregates.WorkflowDefinition;

/** Reads the immutable definition captured by a process, independently of the catalogue. */
@FunctionalInterface
public interface ProcessSnapshotReader {
    WorkflowDefinition read(String snapshot);
}
