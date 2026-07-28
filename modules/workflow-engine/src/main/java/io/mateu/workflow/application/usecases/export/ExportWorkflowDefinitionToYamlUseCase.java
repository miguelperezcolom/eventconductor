package io.mateu.workflow.application.usecases.export;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

/**
 * Serializes a workflow definition to YAML, the same document shape the git and classpath
 * importers read back ({@code objectMapper.treeToValue(node, WorkflowDefinition.class)}), so an
 * exported file can be committed to a definitions repository and re-imported as is. The id is kept
 * on purpose: re-importing then updates the same definition instead of creating a copy.
 */
@Service
@RequiredArgsConstructor
public class ExportWorkflowDefinitionToYamlUseCase {

    private static final YAMLMapper YAML_MAPPER = YAMLMapper.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .build();

    static {
        // Steps leave most optional fields null; omit them so the export stays readable.
        YAML_MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    final WorkflowDefinitionRepository repository;

    public YamlExport handle(String workflowDefinitionId) {
        var definition = repository.findById(workflowDefinitionId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Workflow definition " + workflowDefinitionId + " not found"));
        return new YamlExport(fileName(definition), toYaml(definition));
    }

    private static String toYaml(WorkflowDefinition definition) {
        try {
            return YAML_MAPPER.writeValueAsString(definition);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Could not serialize workflow definition " + definition.id() + " to YAML", e);
        }
    }

    private static String fileName(WorkflowDefinition definition) {
        var slug = definition.name().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (slug.isBlank()) {
            slug = definition.id();
        }
        return slug + "-v" + definition.version() + ".yaml";
    }

    public record YamlExport(String fileName, String content) {}
}
