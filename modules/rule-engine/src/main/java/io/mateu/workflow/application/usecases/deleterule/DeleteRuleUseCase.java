package io.mateu.workflow.application.usecases.deleterule;

import io.mateu.workflow.application.out.RuleCatalogEventPublisher;
import io.mateu.workflow.application.out.RuleCatalogMetrics;
import io.mateu.workflow.application.out.RuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeleteRuleUseCase {

    private final RuleRepository ruleRepository;
    private final RuleCatalogEventPublisher ruleCatalogEventPublisher;
    private final RuleCatalogMetrics ruleCatalogMetrics;

    public void handle(DeleteRuleCommand command) {
        ruleRepository.deleteAllById(List.of(command.ruleId()));
        ruleCatalogEventPublisher.deleted(command.ruleId());
        ruleCatalogMetrics.ruleDeleted(command.ruleId());
        log.info("Rule '{}' deleted", command.ruleId());
    }
}
