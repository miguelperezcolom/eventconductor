package io.mateu.workflow.infra.out.classpath;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.mateu.workflow.application.out.TaskContractRepository;
import io.mateu.workflow.tasks.TaskContract;
import io.mateu.workflow.infra.in.startup.StartupDefinitionImport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads task contracts from {@code classpath:/tasks/} into the JPA repository at startup, the
 * counterpart of {@code ClasspathWorkflowDefinitionImporter} for contracts. Active only when
 * {@code workflow.persistence=jpa} (memory mode uses {@code ClasspathTaskContractRepository}, which
 * self-loads). {@code @Order(-100)} so it runs before the workflow classpath importer
 * ({@code @Order(0)}), which resolves task references as it loads. Saving is idempotent (a contract
 * is keyed by {@code <id>@<version>}).
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
@Order(-100)
@Slf4j
public class ClasspathTaskContractImporter implements StartupDefinitionImport {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new YAMLMapper();

    final TaskContractRepository taskContractRepository;

    @Override
    public void importDefinitions() {
        var resolver = new PathMatchingResourcePatternResolver();
        try {
            var resources = new ArrayList<Resource>();
            for (var pattern : ClasspathTaskContractRepository.PATTERNS) {
                resources.addAll(List.of(resolver.getResources("classpath:/tasks/" + pattern)));
            }
            for (var resource : resources) {
                try {
                    var filename = resource.getFilename();
                    var mapper = filename != null && filename.endsWith(".json") ? JSON : YAML;
                    var content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    var ref = taskContractRepository.save(mapper.readValue(content, TaskContract.class));
                    log.info("Imported task contract '{}' from classpath:tasks/{}", ref, filename);
                } catch (Exception e) {
                    log.error("Failed to import task contract from classpath:tasks/{}", resource.getFilename(), e);
                }
            }
        } catch (Exception e) {
            log.warn("No task contracts found at classpath:/tasks/ — directory may not exist");
        }
    }
}
