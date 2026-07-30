package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.application.out.RuleCatalogMetrics;
import io.mateu.workflow.application.out.RuleRepository;
import io.mateu.workflow.application.out.RuleSource;
import io.mateu.workflow.infra.out.local.LocalRuleSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = RuleRuntimeAutoConfiguration.class)
public class RulesEngineAutoConfiguration {

    /**
     * Default rule source when the catalog lives in the same JVM: the runtime
     * reads straight from the repository.
     */
    @Bean
    @ConditionalOnProperty(name = "rules.source", havingValue = "local", matchIfMissing = true)
    @ConditionalOnMissingBean(RuleSource.class)
    @ConditionalOnBean(RuleRepository.class)
    public RuleSource localRuleSource(RuleRepository ruleRepository) {
        return new LocalRuleSource(ruleRepository);
    }

    // Fallback when Micrometer is absent or no MeterRegistry bean exists —
    // RuleCatalogMetricsAutoConfiguration runs before this and wins when active.
    @Bean
    @ConditionalOnMissingBean(RuleCatalogMetrics.class)
    RuleCatalogMetrics ruleCatalogMetrics() {
        return RuleCatalogMetrics.NOOP;
    }
}
