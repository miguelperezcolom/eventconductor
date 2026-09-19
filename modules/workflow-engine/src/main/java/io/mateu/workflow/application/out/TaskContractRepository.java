package io.mateu.workflow.application.out;

import io.mateu.workflow.tasks.TaskContract;

import java.util.List;
import java.util.Optional;

/**
 * Stores the task contracts the engine knows about. Unlike rules or forms, contracts are
 * <b>versioned</b>: several versions of one id can coexist, because in-flight processes keep running
 * against the version they were pinned to while new definitions pick up the latest (see
 * {@code TaskReferenceResolver} and {@code reference/versioning.md}).
 */
public interface TaskContractRepository {

    /** The exact version of a contract, or empty if that id/version was never imported. */
    Optional<TaskContract> find(String id, int version);

    /** The highest version of a contract, or empty if the id is unknown. */
    Optional<TaskContract> findLatest(String id);

    /** Every stored contract (all ids, all versions). */
    List<TaskContract> findAll();

    /** Store a contract; returns its {@code <id>@<version>} reference. */
    String save(TaskContract contract);
}
