package io.mateu.workflow.webhook;

import java.util.Set;

/**
 * Remembers which definition ids were imported from each git repository, so a later re-import
 * can prune (archive) definitions that have since been removed from that repo. Because it is
 * keyed by repository, pruning only ever touches git-imported definitions — never ones loaded
 * from the classpath or authored by hand.
 *
 * <p>{@code namespace} separates artifact kinds ({@code "workflow"}, {@code "form"},
 * {@code "rule"}) that may share one backing store.
 */
public interface ImportedDefinitionsRegistry {

    /** Ids recorded as imported from this repository on the previous import (never null). */
    Set<String> idsFor(String namespace, String repositoryUrl);

    /** Replaces the recorded id set for this repository with the ids of the latest import. */
    void replace(String namespace, String repositoryUrl, Set<String> ids);
}
