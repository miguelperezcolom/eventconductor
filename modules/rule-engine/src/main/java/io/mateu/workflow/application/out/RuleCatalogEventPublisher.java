package io.mateu.workflow.application.out;

import io.mateu.workflow.domain.Rule;

/**
 * Notifies the outside world (remote runtimes) that the catalog changed.
 */
public interface RuleCatalogEventPublisher {

    void published(Rule rule);

    void deleted(String ruleId);
}
