package io.mateu.workflow.infra.in.async;

import io.mateu.workflow.application.out.RuleSource;
import io.mateu.workflow.application.services.RuleJsonMapper;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.integration.RuleDeleted;
import io.mateu.workflow.dtos.events.integration.RulePublished;
import io.mateu.workflow.infra.out.cache.CachingRuleSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

/**
 * Keeps the local rule cache in sync with the catalog: RulePublished carries
 * the canonical JSON of the saved rule, so the cache is updated without a
 * fetch; RuleDeleted evicts. Enable with rules.kafka-refresh=true and bind
 * consumeRuleCatalogEvent-in-0 to the catalog's rules destination.
 */
@Configuration
@ConditionalOnProperty(name = "rules.kafka-refresh", havingValue = "true")
@ConditionalOnClass(StreamBridge.class)
public class RuleCacheRefreshKafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(RuleCacheRefreshKafkaConsumerConfig.class);

    private final RuleSource ruleSource;
    private final RuleJsonMapper ruleJsonMapper;

    public RuleCacheRefreshKafkaConsumerConfig(RuleSource ruleSource, RuleJsonMapper ruleJsonMapper) {
        this.ruleSource = ruleSource;
        this.ruleJsonMapper = ruleJsonMapper;
    }

    @Bean
    public Consumer<DomainEvent> consumeRuleCatalogEvent() {
        return event -> {
            if (!(ruleSource instanceof CachingRuleSource cache)) {
                if (event instanceof RulePublished || event instanceof RuleDeleted) {
                    ruleSource.refresh();
                }
                return;
            }
            if (event instanceof RulePublished(String ruleId, String name, int version, String ruleJson)) {
                cache.put(ruleJsonMapper.toRule(ruleJson));
                log.info("Rule cache updated from catalog event: {} v{}", ruleId, version);
            } else if (event instanceof RuleDeleted(String ruleId)) {
                cache.invalidate(ruleId);
                log.info("Rule evicted from cache: {}", ruleId);
            }
        };
    }
}
