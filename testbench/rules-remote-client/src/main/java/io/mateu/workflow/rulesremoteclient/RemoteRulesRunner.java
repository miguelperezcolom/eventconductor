package io.mateu.workflow.rulesremoteclient;

import io.mateu.workflow.application.out.RuleSource;
import io.mateu.workflow.application.services.RuleEvaluator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Demonstrates the runtime working against a remote catalog: no rule-engine on
 * the classpath, definitions are pulled over gRPC (or REST) and cached, and
 * evaluation happens locally. Start dev-app or rule-standalone-app first.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RemoteRulesRunner implements ApplicationRunner {

    final RuleSource ruleSource;
    final RuleEvaluator ruleEvaluator;

    @Override
    public void run(ApplicationArguments args) {
        try {
            var rules = ruleSource.findAll();
            log.info("Fetched {} rule(s) from the remote catalog", rules.size());
            rules.forEach(rule -> log.info("  - {} ({}, v{})", rule.id(), rule.type(), rule.version()));

            rules.stream().filter(rule -> "high-value-order".equals(rule.id())).findAny().ifPresent(rule -> {
                var facts = Map.<String, Object>of(
                        "order", Map.of("total", 200),
                        "customer", Map.of("category", "VIP"));
                var result = ruleEvaluator.evaluate("high-value-order", facts);
                log.info("high-value-order evaluated locally: matched={} outputs={}",
                        result.matched(), result.outputs());
            });
        } catch (Exception e) {
            log.warn("Rule catalog not reachable ({}). Start dev-app or rule-standalone-app "
                    + "with rules.grpc.enabled=true and retry.", e.getMessage());
        }
    }

}
