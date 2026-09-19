package io.mateu.workflow.application.usecases.taskcontractimport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.mateu.workflow.application.out.TaskContractRepository;
import io.mateu.workflow.infra.config.TaskContractDirectoryImportProperties;
import io.mateu.workflow.tasks.TaskContract;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Imports task contracts ({@code .ectask}, and {@code .json}/{@code .yaml}/{@code .yml}) from
 * directories on the local filesystem, and is where importing them <em>lives</em>: the git import
 * is a clone followed by exactly this ({@link #importFrom}), the same split workflows, forms and
 * rules have.
 *
 * <p><b>No pruning.</b> Unlike rules or workflows, contracts are versioned and append-only: a
 * process pinned to {@code greet@1} must keep finding {@code greet@1} even after its file is removed
 * from the source, so a contract that leaves the directory is <em>not</em> deleted. A contract also
 * always carries an explicit {@code id}, so there is no derived-id reconciliation to do either.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportTasksFromDirectoryUseCase {

    final TaskContractDirectoryImportProperties directoryImportProperties;
    final TaskContractRepository taskContractRepository;

    // Own mappers: headless embedders may not expose an ObjectMapper bean.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

    /** Re-imports every configured directory. */
    public ImportTasksResult handle() {
        return handle(directoryImportProperties.getDirectories());
    }

    /** Re-imports the given directories. */
    public ImportTasksResult handle(List<String> directories) {
        var imported = new ArrayList<String>();
        var errors = new ArrayList<String>();
        for (var directory : directories) {
            try {
                var root = Path.of(directory).toAbsolutePath().normalize();
                if (!Files.isDirectory(root)) {
                    throw new IOException("not a directory");
                }
                importFrom(root, imported, errors);
            } catch (Exception e) {
                log.error("Failed to import task contracts from directory {}: {}", directory, e.getMessage(), e);
                errors.add("Directory " + directory + ": " + e.getMessage());
            }
        }
        return new ImportTasksResult(imported, errors);
    }

    /** Imports every task contract under {@code root}. */
    public void importFrom(Path root, List<String> imported, List<String> errors) throws IOException {
        try (var stream = Files.walk(root)) {
            stream.filter(ImportTasksFromDirectoryUseCase::isDefinitionFile)
                    .forEach(file -> {
                        try {
                            importFile(file, root, imported);
                        } catch (Exception e) {
                            log.warn("Skipping {}: {}", file, e.getMessage());
                            errors.add("File " + root.relativize(file) + ": " + e.getMessage());
                        }
                    });
        }
    }

    static boolean isDefinitionFile(Path path) {
        String name = path.toString().toLowerCase();
        return name.endsWith(".ectask") || name.endsWith(".json")
                || name.endsWith(".yaml") || name.endsWith(".yml");
    }

    private JsonNode readTree(Path file) throws IOException {
        return file.toString().toLowerCase().endsWith(".json")
                ? OBJECT_MAPPER.readTree(file.toFile())
                : YAML_MAPPER.readTree(file.toFile());
    }

    private void importFile(Path file, Path root, List<String> imported) throws IOException {
        var node = readTree(file);
        // The content decides it is a contract — id, version and group — so a repository holding
        // contracts next to workflows or rules is harmless whatever the files are called.
        if (!node.has("id") || !node.has("version") || !node.has("group")) {
            return;
        }
        var mapper = file.toString().toLowerCase().endsWith(".json") ? OBJECT_MAPPER : YAML_MAPPER;
        var contract = mapper.treeToValue(node, TaskContract.class);
        var ref = taskContractRepository.save(contract);
        imported.add(ref);
        log.info("Imported task contract '{}' from {}", ref, root.relativize(file));
    }

    public record ImportTasksResult(List<String> imported, List<String> errors) {}
}
