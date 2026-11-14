package io.mateu.workflow.infra.out.classpath;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.DefinitionFileFormat;
import io.mateu.workflow.application.services.WorkflowDefinitionValidator;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/**
 * Imports workflow definitions from every {@code workflows/} directory on the classpath (see
 * {@link ClasspathDefinitionFiles}, which also says which copy wins when an id is in two of them) into the JPA repository at startup.
 * Active only when workflow.persistence=jpa. Runs before other ApplicationRunners (@Order(0)).
 * Definitions already present in the DB are skipped (idempotent).
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
@Order(0)
@Slf4j
public class ClasspathWorkflowDefinitionImporter implements ApplicationRunner {

    private static final ObjectMapper YAML_MAPPER = new YAMLMapper();
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    final WorkflowDefinitionRepository workflowDefinitionRepository;
    final WorkflowDefinitionValidator workflowDefinitionValidator;
    final io.mateu.workflow.application.services.TaskReferenceResolver taskReferenceResolver;

    @Override
    public void run(ApplicationArguments args) {
        try {
            // Every workflows/ directory on the classpath; the same id in two of them: the first on
            // the classpath wins (ClasspathDefinitionFiles).
            var firstWins = new ClasspathDefinitionFiles.FirstWins();
            for (var file : ClasspathDefinitionFiles.scan()) {
                try {
                    String filename = file.filename();
                    byte[] bytes = file.bytes();
                    // .ec content may be JSON or YAML; sniff to pick the parser.
                    boolean yaml = DefinitionFileFormat.isYaml(filename, bytes);
                    // The document as written, before binding drops whatever the record has no field
                    // for — the only moment a misspelled key can still be seen. See validateSource.
                    workflowDefinitionValidator.validateSource(
                            (yaml ? YAML_MAPPER : JSON_MAPPER).readTree(bytes), file.location());
                    WorkflowDefinition def = yaml
                            ? YAML_MAPPER.readValue(bytes, WorkflowDefinition.class)
                            : pojoFromJson(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), WorkflowDefinition.class);
                    if (!firstWins.claim(def.id(), file)) {
                        continue;
                    }
                    if (workflowDefinitionRepository.findById(def.id()).isPresent()) {
                        log.info("Workflow definition '{}' already in DB, skipping classpath import", def.id());
                        continue;
                    }
                    if (def.steps() != null) {
                        final String defId = def.id();
                        List<Step> stepsWithId = def.steps().stream()
                                .map(s -> s.withWorkflowDefinitionId(defId))
                                .toList();
                        def = new WorkflowDefinition(
                                def.id(), def.name(), def.version(), def.description(),
                                def.limitConcurrentExecutions(),
                                def.maxConcurrentExecutions(), def.enqueueOnLimit(),
                                def.cronExpression(), def.defaultMaxStepExecutions(), stepsWithId)
                                .withMaxSteps(def.maxSteps())
                                .withLayout(def.layout())
                                .withProcessLock(def.processLock())
                                .withSyncInvocation(def.syncInvocation())
                                .withSchedules(def.schedules())
                                .withTriggers(def.triggers())
                                .withTags(def.tags())
                                .withRetention(def.retention())
                                .withSearchAttributes(def.searchAttributes())
                                .withSensitiveVariables(def.sensitiveVariables())
                                .withUpdates(def.updates())
                                .withStartPolicy(def.startPolicy())
                                .withBusinessKeyPolicy(def.businessKeyPolicy())
                                // The narrower constructor above declares ACTIVE: keep what the file says.
                                .withDeclaredStatus(def.declaredStatus());
                    }
                    workflowDefinitionRepository.save(taskReferenceResolver.resolve(def));
                    log.info("Imported workflow definition '{}' from {}", def.id(), file.location());
                } catch (Exception e) {
                    log.error("Failed to import workflow definition from {}", file.location(), e);
                }
            }
        } catch (IOException e) {
            log.warn("No workflow definitions found at classpath:/workflows/ — directory may not exist");
        }
    }
}
