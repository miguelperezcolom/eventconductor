package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.RuleRepository;
import io.mateu.workflow.application.services.RuleJsonMapper;
import io.mateu.workflow.domain.Rule;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@ConditionalOnProperty(name = "rules.persistence", havingValue = "jpa")
@RequiredArgsConstructor
public class RuleDBRepository implements RuleRepository {

    private final RuleEntityRepository ruleEntityRepository;
    private final RuleJsonMapper ruleJsonMapper;

    @Override
    public Optional<Rule> findById(String id) {
        return ruleEntityRepository.findById(id)
                .map(entity -> ruleJsonMapper.toRule(entity.getRuleJson()));
    }

    @Override
    public String save(Rule rule) {
        ruleEntityRepository.save(new RuleEntity(
                rule.id(), rule.name(),
                rule.type() != null ? rule.type().label() : null,
                rule.version(),
                ruleJsonMapper.toJson(rule)));
        return rule.id();
    }

    @Override
    public List<Rule> findAll() {
        return ruleEntityRepository.findAll().stream()
                .map(entity -> ruleJsonMapper.toRule(entity.getRuleJson()))
                .toList();
    }

    @Override
    public void deleteAllById(List<String> ids) {
        ruleEntityRepository.deleteAllById(ids);
    }
}
