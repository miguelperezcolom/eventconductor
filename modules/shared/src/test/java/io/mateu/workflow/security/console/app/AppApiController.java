package io.mateu.workflow.security.console.app;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The application's own endpoints, and a health probe: not consoles, never touched. */
@RestController
public class AppApiController {

    @GetMapping("/api/hello")
    public String hello() {
        return "hello";
    }

    @GetMapping("/actuator/health")
    public String health() {
        return "UP";
    }

    @GetMapping("/OrderController")
    public String notAUi() {
        return "orders";
    }
}
