package io.mateu.workflow.infra.in.rest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Stands in for rule-engine's read endpoints, which share this class name. */
@RestController
public class RuleReadController {

    @GetMapping("/rules")
    public String list() {
        return "rules";
    }

    @GetMapping("/rules/{id}")
    public String get() {
        return "rule";
    }
}
