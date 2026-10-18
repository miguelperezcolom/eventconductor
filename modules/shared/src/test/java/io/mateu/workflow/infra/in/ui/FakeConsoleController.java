package io.mateu.workflow.infra.in.ui;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Stands in for the engine's console: the generated /_workflow controllers and the task-project zips. */
@RestController
public class FakeConsoleController {

    @GetMapping("/_workflow")
    public String index() {
        return "console-index";
    }

    @PostMapping("/_workflow/mateu/v3/**")
    public String action() {
        return "console-action";
    }

    @GetMapping("/eventconductor/tasks/{group}/module.zip")
    public String zip() {
        return "console-zip";
    }
}
