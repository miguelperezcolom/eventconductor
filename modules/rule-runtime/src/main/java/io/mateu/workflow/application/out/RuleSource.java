package io.mateu.workflow.application.out;

import io.mateu.workflow.domain.Rule;

import java.util.List;
import java.util.Optional;

/**
 * Read port the runtime evaluates rules from. Implementations: same-JVM
 * catalog repository, classpath, REST and gRPC (optionally cached and
 * refreshed via Kafka catalog events).
 */
public interface RuleSource {

    Optional<Rule> findById(String id);

    List<Rule> findAll();

    default void refresh() {
    }
}
