package io.mateu.workflow.infra.in.grpc;

import io.mateu.workflow.application.out.RuleCatalogMetrics;
import io.mateu.workflow.application.out.RuleRepository;
import io.mateu.workflow.application.services.RuleJsonMapper;
import io.mateu.workflow.infra.out.grpc.RuleProtoMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "rules.grpc.enabled", havingValue = "true")
@ConditionalOnClass(io.grpc.Grpc.class)
public class RuleGrpcServerConfiguration {

    @Bean
    public RuleGrpcService ruleGrpcService(RuleRepository ruleRepository, RuleJsonMapper ruleJsonMapper,
                                           RuleCatalogMetrics ruleCatalogMetrics) {
        return new RuleGrpcService(ruleRepository, new RuleProtoMapper(ruleJsonMapper), ruleCatalogMetrics);
    }

    @Bean
    public RuleGrpcServer ruleGrpcServer(@Value("${rules.grpc.port:9090}") int port,
                                         RuleGrpcService ruleGrpcService) {
        return new RuleGrpcServer(port, ruleGrpcService);
    }
}
