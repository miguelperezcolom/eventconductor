package io.mateu.workflow.infra.in.ui.pages.taskcontracts;

import io.mateu.workflow.codegen.ProjectSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the generated worker project for a task group as a zip download. It is a plain HTTP
 * endpoint rather than a UI-framework command so the browser downloads the file directly; the Tasks
 * view links to it.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class TaskProjectController {

    private final TaskProjectService taskProjectService;

    @GetMapping("/eventconductor/tasks/{group}/module.zip")
    public ResponseEntity<byte[]> module(@PathVariable String group) {
        return zip(group, ProjectSpec.Variant.TASK_MODULE, group + "-tasks");
    }

    @GetMapping("/eventconductor/tasks/{group}/service.zip")
    public ResponseEntity<byte[]> service(@PathVariable String group) {
        return zip(group, ProjectSpec.Variant.TASK_SERVICE, group + "-service");
    }

    private ResponseEntity<byte[]> zip(String group, ProjectSpec.Variant variant, String name) {
        byte[] archive = taskProjectService.zip(group, variant);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + ".zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(archive);
    }
}
