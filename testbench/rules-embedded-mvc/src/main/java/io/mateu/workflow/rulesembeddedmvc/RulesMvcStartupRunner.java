package io.mateu.workflow.rulesembeddedmvc;

import io.mateu.workflow.application.services.RuleJsonMapper;
import io.mateu.workflow.application.usecases.saverule.SaveRuleCommand;
import io.mateu.workflow.application.usecases.saverule.SaveRuleUseCase;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Seeds the rule catalog and starts a process whose RULE step evaluates the
 * discount rule against the process variables — all in the same JVM.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RulesMvcStartupRunner implements ApplicationRunner {

    final SaveRuleUseCase saveRuleUseCase;
    final RuleJsonMapper ruleJsonMapper;
    final ProcessUpstreamEventUseCase processUpstreamEventUseCase;

    @Override
    public void run(ApplicationArguments args) {
        saveRuleUseCase.handle(new SaveRuleCommand(ruleJsonMapper.toRule("""
                id: high-value-order
                name: High value order approval
                type: expression
                salience: 10
                tags: [orders]
                when: "order.total > 100 && customer.category == 'VIP'"
                then:
                  - name: discount
                    expression: "order.total * 0.1"
                  - name: approvalRequired
                    expression: "true"
                """)));
        log.info("Rule catalog seeded");

        processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(
                new ProcessCreationRequested(
                        "rule-demo",
                        "my-first-rule-process",
                        List.of(new Variable("order.total", "200"),
                                new Variable("customer.category", "VIP"))
                )));
        log.info("rule-demo process started");
    }

}
