package io.mateu.workflow.application.usecases.saverule;

import io.mateu.workflow.application.out.RuleCatalogEventPublisher;
import io.mateu.workflow.application.out.RuleCatalogMetrics;
import io.mateu.workflow.application.out.RuleRepository;
import io.mateu.workflow.application.services.RuleValidator;
import io.mateu.workflow.domain.Rule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SaveRuleUseCase {

    private final RuleRepository ruleRepository;
    private final RuleValidator ruleValidator;
    private final RuleCatalogEventPublisher ruleCatalogEventPublisher;
    private final RuleCatalogMetrics ruleCatalogMetrics;

    public String handle(SaveRuleCommand command) {
        var rule = command.rule();
        if (rule.id() == null || rule.id().isBlank()) {
            rule = withId(rule, UUID.randomUUID().toString());
        }
        ruleValidator.validate(rule);
        var id = ruleRepository.save(rule);
        ruleCatalogEventPublisher.published(rule);
        ruleCatalogMetrics.ruleSaved(id);
        log.info("Rule '{}' saved and published", id);
        return id;
    }

    private Rule withId(Rule rule, String id) {
        return new Rule(id, rule.name(), rule.description(), rule.type(), rule.version(),
                rule.salience(), rule.tags(), rule.when(), rule.then(),
                rule.inputs(), rule.outputs(), rule.rows(), rule.hitPolicy());
    }
}
