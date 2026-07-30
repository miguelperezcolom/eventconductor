package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.application.out.RuleRuntimeMetrics;
import io.mateu.workflow.application.out.RuleSource;
import io.mateu.workflow.application.services.FactCoercer;
import io.mateu.workflow.application.services.RuleEvaluator;
import io.mateu.workflow.application.services.RuleJsonMapper;
import io.mateu.workflow.application.usecases.evaluaterule.EvaluateRuleUseCase;
import io.mateu.workflow.infra.out.cache.CachingRuleSource;
import io.mateu.workflow.infra.out.classpath.ClasspathRuleSource;
import io.mateu.workflow.infra.out.grpc.GrpcRuleSource;
import io.mateu.workflow.infra.out.grpc.RuleProtoMapper;
import io.mateu.workflow.infra.out.rest.RestRuleSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(RulesRuntimeProperties.class)
public class RuleRuntimeAutoConfiguration {

    // Fallback when Micrometer is absent or no MeterRegistry bean exists —
    // RuleRuntimeMetricsAutoConfiguration runs before this and wins when active.
    @Bean
    @ConditionalOnMissingBean(RuleRuntimeMetrics.class)
    public RuleRuntimeMetrics ruleRuntimeMetrics() {
        return RuleRuntimeMetrics.NOOP;
    }

    @Bean
    @ConditionalOnMissingBean
    public RuleJsonMapper ruleJsonMapper() {
        return new RuleJsonMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public FactCoercer factCoercer() {
        return new FactCoercer();
    }

    @Bean
    @ConditionalOnMissingBean(RuleSource.class)
    @ConditionalOnProperty(name = "rules.source", havingValue = "classpath")
    public RuleSource classpathRuleSource(RuleJsonMapper ruleJsonMapper) {
        return new ClasspathRuleSource(ruleJsonMapper);
    }

    @Bean
    @ConditionalOnMissingBean(RuleSource.class)
    @ConditionalOnProperty(name = "rules.source", havingValue = "rest")
    public RuleSource restRuleSource(RulesRuntimeProperties properties, RuleJsonMapper ruleJsonMapper,
                                     RuleRuntimeMetrics metrics) {
        return new CachingRuleSource(
                new RestRuleSource(properties.getCatalog().getUrl(), ruleJsonMapper),
                properties.getCache().getTtl(), metrics);
    }

    @Bean
    @ConditionalOnMissingBean(RuleSource.class)
    @ConditionalOnProperty(name = "rules.source", havingValue = "grpc")
    @ConditionalOnClass(io.grpc.ManagedChannelBuilder.class)
    public RuleSource grpcRuleSource(RulesRuntimeProperties properties, RuleJsonMapper ruleJsonMapper,
                                     RuleRuntimeMetrics metrics) {
        return new CachingRuleSource(
                new GrpcRuleSource(properties.getCatalog().getGrpcTarget(), new RuleProtoMapper(ruleJsonMapper)),
                properties.getCache().getTtl(), metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public RuleEvaluator ruleEvaluator(ObjectProvider<RuleSource> ruleSource, RuleRuntimeMetrics metrics) {
        return new RuleEvaluator(ruleSource.getIfAvailable(), metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public EvaluateRuleUseCase evaluateRuleUseCase(RuleEvaluator ruleEvaluator) {
        return new EvaluateRuleUseCase(ruleEvaluator);
    }
}
