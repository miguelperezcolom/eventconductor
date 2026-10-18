package io.mateu.workflow.infra.in.ui.pages.taskcontracts;

import io.mateu.workflow.codegen.ProjectSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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

    /** What a group name may be; anything else (quotes, CR/LF, slashes) never reaches a header. */
    static final java.util.regex.Pattern GROUP = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    private ResponseEntity<byte[]> zip(String group, ProjectSpec.Variant variant, String name) {
        if (group == null || !GROUP.matcher(group).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a task group name.");
        }
        byte[] archive = taskProjectService.zip(group, variant);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(name + ".zip").build().toString())
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(archive);
    }
}
