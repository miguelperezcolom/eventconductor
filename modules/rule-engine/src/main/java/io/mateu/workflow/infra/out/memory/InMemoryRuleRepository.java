package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.RuleRepository;
import io.mateu.workflow.domain.Rule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@ConditionalOnProperty(name = "rules.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryRuleRepository implements RuleRepository {

    private final ConcurrentHashMap<String, Rule> store = new ConcurrentHashMap<>();

    @Override
    public Optional<Rule> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public String save(Rule rule) {
        store.put(rule.id(), rule);
        return rule.id();
    }

    @Override
    public List<Rule> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public void deleteAllById(List<String> ids) {
        ids.forEach(store::remove);
    }
}
