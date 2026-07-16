package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.application.out.RuleCatalogEventPublisher;
import io.mateu.workflow.application.services.RuleJsonMapper;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.dtos.events.integration.RuleDeleted;
import io.mateu.workflow.dtos.events.integration.RulePublished;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

/**
 * Kafka mode: publishes catalog changes on the "rules" binding so remote
 * runtimes keep their caches fresh. RulePublished carries the canonical JSON
 * of the rule to spare consumers a fetch.
 */
@Service
@ConditionalOnProperty(name = "workflow.mode", havingValue = "kafka")
@RequiredArgsConstructor
@Slf4j
public class KafkaRuleCatalogEventPublisher implements RuleCatalogEventPublisher {

    private final StreamBridge streamBridge;
    private final RuleJsonMapper ruleJsonMapper;

    @Override
    public void published(Rule rule) {
        streamBridge.send("rules",
                new RulePublished(rule.id(), rule.name(), rule.version(), ruleJsonMapper.toJson(rule)));
        log.info("RulePublished emitted for '{}'", rule.id());
    }

    @Override
    public void deleted(String ruleId) {
        streamBridge.send("rules", new RuleDeleted(ruleId));
        log.info("RuleDeleted emitted for '{}'", ruleId);
    }
}
