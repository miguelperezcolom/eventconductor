package io.mateu.workflow.infra.out.local;

import io.mateu.workflow.application.out.RuleRepository;
import io.mateu.workflow.application.out.RuleSource;
import io.mateu.workflow.domain.Rule;

import java.util.List;
import java.util.Optional;

/**
 * Same-JVM source: the runtime reads straight from the catalog repository when
 * both live in the same application (rules.source=local, the default).
 */
public class LocalRuleSource implements RuleSource {

    private final RuleRepository ruleRepository;

    public LocalRuleSource(RuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    @Override
    public Optional<Rule> findById(String id) {
        return ruleRepository.findById(id);
    }

    @Override
    public List<Rule> findAll() {
        return ruleRepository.findAll();
    }
}
