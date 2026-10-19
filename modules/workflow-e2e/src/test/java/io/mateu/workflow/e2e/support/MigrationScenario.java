package io.mateu.workflow.e2e.support;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/**
 * Deploys another version of a test definition — read from {@code classpath:/migration/} — the way an
 * import would, and puts the first version back afterwards, so the migration tests can move processes
 * between versions without other tests ever seeing a version they did not write.
 */
public final class MigrationScenario {

    private final WorkflowDefinitionRepository definitions;
    private final Map<String, WorkflowDefinition> originals = new ConcurrentHashMap<>();

    public MigrationScenario(WorkflowDefinitionRepository definitions) {
        this.definitions = definitions;
    }

    /** Deploys {@code classpath:/migration/<file>} as the definition's current version, remembering the one it replaces. */
    public WorkflowDefinition deploy(String file) {
        var definition = read("/migration/" + file);
        originals.computeIfAbsent(definition.id(), id -> definitions.findById(id).orElseThrow());
        definitions.save(definition);
        return definitions.findById(definition.id()).orElseThrow();
    }

    /** Puts back every definition {@link #deploy} replaced. */
    public void restore() {
        originals.values().forEach(definitions::save);
        originals.clear();
    }

    static WorkflowDefinition read(String resource) {
        try (var in = MigrationScenario.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("No " + resource + " on the classpath");
            }
            var bytes = in.readAllBytes();
            WorkflowDefinition definition = resource.endsWith(".yaml")
                    ? new YAMLMapper().readValue(bytes, WorkflowDefinition.class)
                    : pojoFromJson(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), WorkflowDefinition.class);
            var id = definition.id();
            return definition.withSteps(definition.steps().stream().map(s -> s.withWorkflowDefinitionId(id)).toList());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The step ids of a definition, in order — for assertions. */
    public static List<String> stepIds(WorkflowDefinition definition) {
        return definition.steps().stream().map(s -> s.id()).toList();
    }
}
