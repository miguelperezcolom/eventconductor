package io.mateu.workflow.infra.out.classpath;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.DefinitionFileFormat;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/**
 * Imports workflow definitions from classpath:/workflows/ into the JPA repository at startup.
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

    final WorkflowDefinitionRepository workflowDefinitionRepository;

    @Override
    public void run(ApplicationArguments args) {
        var resolver = new PathMatchingResourcePatternResolver();
        try {
            var resources = new java.util.ArrayList<Resource>();
            resources.addAll(Arrays.asList(resolver.getResources("classpath:/workflows/*.json")));
            resources.addAll(Arrays.asList(resolver.getResources("classpath:/workflows/*.{yaml,yml}")));
            resources.addAll(Arrays.asList(resolver.getResources("classpath:/workflows/*.ec")));
            for (var resource : resources) {
                try {
                    String filename = resource.getFilename();
                    byte[] bytes = resource.getInputStream().readAllBytes();
                    // .ec content may be JSON or YAML; sniff to pick the parser.
                    WorkflowDefinition def = DefinitionFileFormat.isYaml(filename, bytes)
                            ? YAML_MAPPER.readValue(bytes, WorkflowDefinition.class)
                            : pojoFromJson(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), WorkflowDefinition.class);
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
                                def.cronExpression(), def.defaultMaxStepExecutions(), stepsWithId);
                    }
                    workflowDefinitionRepository.save(def);
                    log.info("Imported workflow definition '{}' from classpath:{}", def.id(), filename);
                } catch (Exception e) {
                    log.error("Failed to import workflow definition from classpath:{}", resource.getFilename(), e);
                }
            }
        } catch (IOException e) {
            log.warn("No workflow definitions found at classpath:/workflows/ — directory may not exist");
        }
    }
}
