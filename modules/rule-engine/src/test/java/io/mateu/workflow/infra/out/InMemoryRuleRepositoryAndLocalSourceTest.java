package io.mateu.workflow.infra.out;

import io.mateu.workflow.domain.Assignment;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.domain.RuleType;
import io.mateu.workflow.infra.out.local.LocalRuleSource;
import io.mateu.workflow.infra.out.memory.InMemoryRuleRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRuleRepositoryAndLocalSourceTest {

    private Rule rule(String id) {
        return new Rule(id, id, null, RuleType.EXPRESSION, 1, 0, null,
                null, List.of(new Assignment("out", "1")), null, null, null, null);
    }

    @Test
    void crudRoundTrip() {
        var repository = new InMemoryRuleRepository();

        assertThat(repository.save(rule("r1"))).isEqualTo("r1");
        assertThat(repository.findById("r1")).isPresent();
        assertThat(repository.findAll()).hasSize(1);

        repository.deleteAllById(List.of("r1"));
        assertThat(repository.findById("r1")).isEmpty();
    }

    @Test
    void localSourceReadsTheRepository() {
        var repository = new InMemoryRuleRepository();
        repository.save(rule("r1"));
        var source = new LocalRuleSource(repository);

        assertThat(source.findById("r1")).isPresent();
        assertThat(source.findById("nope")).isEmpty();
        assertThat(source.findAll()).hasSize(1);
    }
}
