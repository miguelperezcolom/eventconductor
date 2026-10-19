package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.crypto.VariableEncryption;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Fails startup when the definitions this engine already holds declare sensitive variables and no
 * encryption key is configured — the case a definition load cannot catch: the keys were removed from a
 * running deployment's configuration. Starting anyway would store the next values of those variables in
 * clear and fail on every sealed one it reads back.
 *
 * <p>Runs first among the application runners, before the classpath importer re-imports anything (which
 * refuses such a definition on its own: {@link WorkflowDefinitionValidator#requireKeysFor}).
 */
@Component
@lombok.extern.slf4j.Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SensitiveVariablesStartupCheck implements ApplicationRunner {

    private final ObjectProvider<VariableEncryption> encryption;
    private final ObjectProvider<WorkflowDefinitionRepository> definitions;

    public SensitiveVariablesStartupCheck(ObjectProvider<VariableEncryption> encryption,
                                          ObjectProvider<WorkflowDefinitionRepository> definitions) {
        this.encryption = encryption;
        this.definitions = definitions;
    }

    @Override
    public void run(ApplicationArguments args) {
        check();
    }

    /** Throws when a stored definition declares sensitive variables and no key is configured. */
    public void check() {
        var configured = encryption.getIfAvailable(() -> VariableEncryption.DISABLED);
        if (configured.enabled()) {
            return;
        }
        var repository = definitions.getIfAvailable();
        if (repository == null) {
            return;
        }
        java.util.List<io.mateu.workflow.domain.aggregates.WorkflowDefinition> stored;
        try {
            stored = repository.findAll();
        } catch (RuntimeException unreachable) {
            // The engine boots with its database down (it resumes when it comes back): the check is then
            // skipped, and the repositories still refuse to write a sensitive variable in clear.
            log.warn("Could not check the stored definitions for sensitive variables at startup ({}); writes of"
                    + " sensitive variables without a key are still refused", unreachable.getClass().getSimpleName());
            return;
        }
        var offending = stored.stream()
                .filter(definition -> definition.declaresSensitiveVariables())
                .map(definition -> definition.id())
                .toList();
        if (!offending.isEmpty()) {
            throw new IllegalStateException("Workflow definitions " + offending + " declare sensitive variables but no"
                    + " encryption key is configured: set workflow.encryption.keys.<id> (and workflow.encryption.active-key)"
                    + " with the keys their processes were sealed with. Without them the engine would store new values"
                    + " in clear and could not open the stored ones.");
        }
    }
}
