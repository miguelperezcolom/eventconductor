package io.mateu.workflow.codegen;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * What a project skeleton needs to be generated: the {@link Variant}, the Maven coordinates, the
 * parent version, where the definitions come from (a Maven artifact via {@code definitions}, or a
 * git {@code repository} + {@code ref}), which tasks to build (a whole {@code group} or an explicit
 * {@code tasks} list), the base package, and the contracts to describe in the README. A field left
 * null is simply omitted from the generated {@code pom}, so the two definition sources and the two
 * task selectors are mutually exclusive by construction.
 */
public record ProjectSpec(
        Variant variant,
        String groupId,
        String artifactId,
        String version,
        String parentVersion,
        String definitions,
        String repository,
        String ref,
        String group,
        String tasks,
        String basePackage,
        List<JsonNode> contracts,
        boolean includeWrapper,
        boolean includeGitignore) {

    public enum Variant {
        /** A library added to an existing Spring Boot service. */
        TASK_MODULE,
        /** A runnable standalone Spring Boot task service. */
        TASK_SERVICE
    }

    public ProjectSpec {
        contracts = contracts == null ? List.of() : List.copyOf(contracts);
    }
}
