package io.mateu.workflow.application.usecases;

import io.mateu.workflow.application.out.RuleCatalogEventPublisher;
import io.mateu.workflow.application.services.RuleValidator;
import io.mateu.workflow.application.usecases.deleterule.DeleteRuleCommand;
import io.mateu.workflow.application.usecases.deleterule.DeleteRuleUseCase;
import io.mateu.workflow.application.usecases.saverule.SaveRuleCommand;
import io.mateu.workflow.application.usecases.saverule.SaveRuleUseCase;
import io.mateu.workflow.domain.Assignment;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.domain.RuleType;
import io.mateu.workflow.infra.out.async.NoopRuleCatalogEventPublisher;
import io.mateu.workflow.infra.out.memory.InMemoryRuleRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SaveAndDeleteRuleUseCaseTest {

    static RuleValidator validator;

    @BeforeAll
    static void init() throws IOException {
        validator = new RuleValidator();
        validator.init();
    }

    private Rule rule(String id) {
        return new Rule(id, "Rule", null, RuleType.EXPRESSION, 1, 0, null,
                null, List.of(new Assignment("out", "1")), null, null, null, null);
    }

    @Test
    void saveAssignsIdValidatesStoresAndPublishes() {
        var repository = new InMemoryRuleRepository();
        var published = new ArrayList<String>();
        var useCase = new SaveRuleUseCase(repository, validator, new RuleCatalogEventPublisher() {
            @Override
            public void published(Rule rule) {
                published.add(rule.id());
            }

            @Override
            public void deleted(String ruleId) {
            }
        });

        var id = useCase.handle(new SaveRuleCommand(rule(null)));

        assertThat(id).isNotBlank();
        assertThat(repository.findById(id)).isPresent();
        assertThat(published).containsExactly(id);
    }

    @Test
    void saveKeepsProvidedId() {
        var repository = new InMemoryRuleRepository();
        var useCase = new SaveRuleUseCase(repository, validator, new NoopRuleCatalogEventPublisher());

        var id = useCase.handle(new SaveRuleCommand(rule("my-rule")));

        assertThat(id).isEqualTo("my-rule");
    }

    @Test
    void invalidRuleIsRejectedAndNotStored() {
        var repository = new InMemoryRuleRepository();
        var useCase = new SaveRuleUseCase(repository, validator, new NoopRuleCatalogEventPublisher());
        var invalid = new Rule("bad", "Bad", null, RuleType.EXPRESSION, 1, 0, null,
                null, null, null, null, null, null);

        assertThatThrownBy(() -> useCase.handle(new SaveRuleCommand(invalid)))
                .isInstanceOf(RuleValidator.RuleValidationException.class);
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void deleteRemovesAndPublishes() {
        var repository = new InMemoryRuleRepository();
        var deleted = new ArrayList<String>();
        repository.save(rule("to-delete"));
        var useCase = new DeleteRuleUseCase(repository, new RuleCatalogEventPublisher() {
            @Override
            public void published(Rule rule) {
            }

            @Override
            public void deleted(String ruleId) {
                deleted.add(ruleId);
            }
        });

        useCase.handle(new DeleteRuleCommand("to-delete"));

        assertThat(repository.findAll()).isEmpty();
        assertThat(deleted).containsExactly("to-delete");
    }
}
