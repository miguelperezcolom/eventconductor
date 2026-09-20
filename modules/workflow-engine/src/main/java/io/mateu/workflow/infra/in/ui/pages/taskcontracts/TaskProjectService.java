package io.mateu.workflow.infra.in.ui.pages.taskcontracts;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.out.TaskContractRepository;
import io.mateu.workflow.codegen.ProjectGenerator;
import io.mateu.workflow.codegen.ProjectSpec;
import io.mateu.workflow.codegen.ProjectZipper;
import io.mateu.workflow.tasks.TaskContract;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

/**
 * Builds a downloadable worker project for a task group, using the very generator the Maven goal and
 * the IDE use (decision 16). The archive bundles the generated project skeleton (pom, README, and —
 * for a service — the wrapper) together with the group's {@code .ectask} contracts under
 * {@code src/main/resources/tasks}, so the download builds on its own from the local contracts.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class TaskProjectService {

    /** The EventConductor version the generated project builds against (the parents' version). */
    static final String EC_VERSION = "1.0-SNAPSHOT";
    private static final String GROUP_ID = "com.example";
    private static final String BASE_PACKAGE = "com.example";
    private static final String PROJECT_VERSION = "0.1.0-SNAPSHOT";

    private static final ObjectMapper ECTASK = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final TaskContractRepository taskContractRepository;

    public byte[] zip(String group, ProjectSpec.Variant variant) {
        var contracts = contractsOf(group);
        var nodes = contracts.stream().map(c -> (JsonNode) ECTASK.valueToTree(c)).toList();
        var artifactId = artifactId(group, variant);
        var spec = new ProjectSpec(variant, GROUP_ID, artifactId, PROJECT_VERSION, EC_VERSION,
                null, null, null, group, null, BASE_PACKAGE, nodes,
                variant == ProjectSpec.Variant.TASK_SERVICE, variant == ProjectSpec.Variant.TASK_SERVICE);
        var project = new ProjectGenerator().generate(spec);

        var contractFiles = new LinkedHashMap<String, String>();
        for (var contract : contracts) {
            contractFiles.put("src/main/resources/tasks/" + contract.id() + "@" + contract.version() + ".ectask",
                    toEctask(contract));
        }
        return ProjectZipper.zip(project, contractFiles);
    }

    /** The Maven dependency snippet for the module a service adds to depend on this group's tasks. */
    public String dependencySnippet(String group) {
        return "<dependency>\n"
                + "    <groupId>" + GROUP_ID + "</groupId>\n"
                + "    <artifactId>" + artifactId(group, ProjectSpec.Variant.TASK_MODULE) + "</artifactId>\n"
                + "    <version>" + PROJECT_VERSION + "</version>\n"
                + "</dependency>";
    }

    private List<TaskContract> contractsOf(String group) {
        var contracts = taskContractRepository.findAll().stream()
                .filter(c -> group.equals(c.group()))
                .toList();
        if (contracts.isEmpty()) {
            throw new IllegalArgumentException("No task contracts in group '" + group + "'.");
        }
        return contracts;
    }

    private static String artifactId(String group, ProjectSpec.Variant variant) {
        return group + (variant == ProjectSpec.Variant.TASK_SERVICE ? "-service" : "-tasks");
    }

    private static String toEctask(TaskContract contract) {
        try {
            return ECTASK.writerWithDefaultPrettyPrinter().writeValueAsString(contract);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise contract " + contract.ref(), e);
        }
    }
}
