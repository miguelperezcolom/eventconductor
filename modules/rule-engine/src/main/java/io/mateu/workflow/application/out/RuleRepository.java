package io.mateu.workflow.application.out;

import io.mateu.workflow.domain.Rule;

import java.util.List;
import java.util.Optional;

/**
 * Catalog port. Deliberately framework-free (unlike FormRepository it does not
 * extend the Mateu CrudStore) so the Rule domain model can stay in the
 * lightweight rule-runtime module; the UI adapts it through a view record.
 */
public interface RuleRepository {

    Optional<Rule> findById(String id);

    String save(Rule rule);

    List<Rule> findAll();

    void deleteAllById(List<String> ids);
}
