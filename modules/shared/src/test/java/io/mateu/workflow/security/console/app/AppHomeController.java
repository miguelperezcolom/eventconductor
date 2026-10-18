package io.mateu.workflow.security.console.app;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** What Mateu generates for {@link AppHome}: its index page. */
@RestController
public class AppHomeController {

    @GetMapping("/")
    public String index() {
        return "app-index";
    }
}
