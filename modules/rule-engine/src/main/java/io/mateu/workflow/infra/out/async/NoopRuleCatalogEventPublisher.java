package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.application.out.RuleCatalogEventPublisher;
import io.mateu.workflow.domain.Rule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Embedded mode: consumers live in the same JVM and read the repository
 * directly, so catalog changes need no publication.
 */
@Service
@ConditionalOnProperty(name = "workflow.mode", havingValue = "embedded", matchIfMissing = true)
@Slf4j
public class NoopRuleCatalogEventPublisher implements RuleCatalogEventPublisher {

    @Override
    public void published(Rule rule) {
        log.debug("Rule '{}' published (embedded mode, no event emitted)", rule.id());
    }

    @Override
    public void deleted(String ruleId) {
        log.debug("Rule '{}' deleted (embedded mode, no event emitted)", ruleId);
    }
}
