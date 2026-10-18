package io.mateu.workflow.security.console.app;

import io.mateu.core.infra.MateuController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** What Mateu generates for {@link AppHome}: its action endpoint, which can route to the engine's pages. */
@RestController
public class AppHomeMateuController implements MateuController {

    @PostMapping("/mateu/v3/**")
    public String action() {
        return "app-action";
    }
}
