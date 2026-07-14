package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.workflow.application.out.RuleRepository;
import io.mateu.workflow.application.services.RuleJsonMapper;
import io.mateu.workflow.application.usecases.deleterule.DeleteRuleCommand;
import io.mateu.workflow.application.usecases.deleterule.DeleteRuleUseCase;
import io.mateu.workflow.application.usecases.saverule.SaveRuleCommand;
import io.mateu.workflow.application.usecases.saverule.SaveRuleUseCase;
import io.mateu.workflow.domain.Rule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Adapts the framework-free RuleRepository port to the Mateu CrudRepository
 * the UI expects, mapping rules to RuleRow views. Saving parses the definition
 * text and goes through the save use case (validation + publication included).
 */
@Service
@RequiredArgsConstructor
public class RuleRowsRepository implements CrudRepository<RuleRow> {

    private final RuleRepository ruleRepository;
    private final RuleJsonMapper ruleJsonMapper;
    private final SaveRuleUseCase saveRuleUseCase;
    private final DeleteRuleUseCase deleteRuleUseCase;

    @Override
    public Optional<RuleRow> findById(String id) {
        return ruleRepository.findById(id).map(this::toRow);
    }

    @Override
    public String save(RuleRow row) {
        var rule = ruleJsonMapper.toRule(row.definition());
        return saveRuleUseCase.handle(new SaveRuleCommand(rule));
    }

    @Override
    public List<RuleRow> findAll() {
        return ruleRepository.findAll().stream().map(this::toRow).toList();
    }

    @Override
    public void deleteAllById(List<String> ids) {
        ids.forEach(id -> deleteRuleUseCase.handle(new DeleteRuleCommand(id)));
    }

    private RuleRow toRow(Rule rule) {
        return new RuleRow(rule.id(), rule.name(),
                rule.type() != null ? rule.type().label() : null,
                rule.version(), ruleJsonMapper.toJson(rule));
    }
}
