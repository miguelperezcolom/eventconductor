package io.mateu.workflow.rulesembeddedheadless;

import io.mateu.workflow.application.services.RuleEvaluator;
import io.mateu.workflow.application.usecases.saverule.SaveRuleCommand;
import io.mateu.workflow.application.usecases.saverule.SaveRuleUseCase;
import io.mateu.workflow.domain.Assignment;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.domain.RuleType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class RulesStartupRunner implements ApplicationRunner {

    final RuleEvaluator ruleEvaluator;
    final SaveRuleUseCase saveRuleUseCase;

    @Override
    public void run(ApplicationArguments args) {
        var facts = Map.<String, Object>of(
                "order", Map.of("total", 200),
                "customer", Map.of("category", "VIP"));

        // Expression rule, loaded from classpath:/rules/high-value-order.yaml
        var discount = ruleEvaluator.evaluate("high-value-order", facts);
        log.info("high-value-order matched={} outputs={}", discount.matched(), discount.outputs());

        // Decision table, loaded from classpath:/rules/shipping-costs.json
        var shipping = ruleEvaluator.evaluate("shipping-costs", facts);
        log.info("shipping-costs matched={} outputs={}", shipping.matched(), shipping.outputs());

        // The catalog use case validates and stores rules programmatically too
        var id = saveRuleUseCase.handle(new SaveRuleCommand(new Rule(
                null, "Always approve small orders", null, RuleType.EXPRESSION, 1, 0, List.of("orders"),
                "order.total <= 100",
                List.of(new Assignment("approvalRequired", "false")),
                null, null, null, null)));
        log.info("Rule saved in the catalog with id {}", id);
    }

}
