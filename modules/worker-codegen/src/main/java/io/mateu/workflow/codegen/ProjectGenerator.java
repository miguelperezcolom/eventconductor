package io.mateu.workflow.codegen;

import com.fasterxml.jackson.databind.JsonNode;
import io.mateu.workflow.codegen.GeneratedProject.GeneratedProjectFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds a project skeleton for a set of task contracts — the single source of project templates
 * the Maven goal, the IDE and the UI all share (decision 16). A generated project is only a
 * {@code pom} (parent + coordinates + a few {@code ec.*} properties), a README written from the
 * contracts, and — for a standalone service — the Maven wrapper, a {@code .gitignore} and an
 * {@code application.yaml}. All build logic lives in the parents (decisions 14/15), so nothing here
 * carries steps or plugins; the Java is generated at build time by {@link TaskSourceGenerator}.
 *
 * <p>Pure and deterministic: same spec, same bytes.
 */
public final class ProjectGenerator {

    public GeneratedProject generate(ProjectSpec spec) {
        var files = new ArrayList<GeneratedProjectFile>();
        files.add(new GeneratedProjectFile("pom.xml", pom(spec)));
        files.add(new GeneratedProjectFile("README.md", readme(spec)));

        if (spec.variant() == ProjectSpec.Variant.TASK_SERVICE) {
            files.add(new GeneratedProjectFile("src/main/resources/application.yaml", applicationYaml(spec)));
            if (spec.includeGitignore()) {
                files.add(new GeneratedProjectFile(".gitignore", resource("gitignore")));
            }
            if (spec.includeWrapper()) {
                files.add(new GeneratedProjectFile("mvnw", resource("mvnw")));
                files.add(new GeneratedProjectFile("mvnw.cmd", resource("mvnw.cmd")));
                files.add(new GeneratedProjectFile(".mvn/wrapper/maven-wrapper.properties",
                        resource("maven-wrapper.properties")));
            }
        }
        return new GeneratedProject(files);
    }

    private String pom(ProjectSpec spec) {
        var parent = spec.variant() == ProjectSpec.Variant.TASK_SERVICE
                ? "task-service-parent" : "task-module-parent";
        var properties = new StringBuilder();
        appendProperty(properties, "ec.definitions", spec.definitions());
        appendProperty(properties, "ec.repository", spec.repository());
        appendProperty(properties, "ec.ref", spec.ref());
        appendProperty(properties, "ec.group", spec.group());
        appendProperty(properties, "ec.tasks", spec.tasks());
        appendProperty(properties, "ec.basePackage", spec.basePackage());

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>

                    <parent>
                        <groupId>io.mateu.workflow</groupId>
                        <artifactId>%s</artifactId>
                        <version>%s</version>
                    </parent>

                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>

                    <properties>
                %s    </properties>
                </project>
                """.formatted(parent, orDefault(spec.parentVersion(), "1.0-SNAPSHOT"),
                orDefault(spec.groupId(), "com.example"), orDefault(spec.artifactId(), "tasks"),
                orDefault(spec.version(), "0.1.0-SNAPSHOT"), properties.toString());
    }

    private static void appendProperty(StringBuilder out, String key, String value) {
        if (value != null && !value.isBlank()) {
            out.append("        <").append(key).append('>').append(escapeXml(value))
                    .append("</").append(key).append(">\n");
        }
    }

    private String applicationYaml(ProjectSpec spec) {
        return """
                # Only the service's own settings live here; everything else has a sensible default
                # and is overridable by an environment variable.
                spring:
                  application:
                    name: %s
                  cloud:
                    stream:
                      kafka:
                        binder:
                          # KAFKA_BROKERS overrides the broker list; the consumer group defaults to
                          # the application name (see the worker-kafka binding defaults).
                          brokers: ${KAFKA_BROKERS:localhost:9092}
                """.formatted(orDefault(spec.artifactId(), "tasks"));
    }

    private String readme(ProjectSpec spec) {
        var out = new StringBuilder();
        out.append("# ").append(orDefault(spec.artifactId(), "tasks")).append("\n\n");
        out.append(spec.variant() == ProjectSpec.Variant.TASK_SERVICE
                ? "A standalone EventConductor task service. Run it with `./mvnw spring-boot:run` "
                        + "(or build an image with `./mvnw spring-boot:build-image`).\n\n"
                : "An EventConductor task module. Add it as a dependency of any Spring Boot service "
                        + "and implement the interfaces below.\n\n");
        out.append("The task interfaces and their input/output types are generated at build time "
                + "under `target/generated-sources` — you never edit them. Implement each interface "
                + "as a Spring bean; the engine dispatches to it.\n\n");
        out.append("## Tasks to implement\n");

        for (var contract : spec.contracts()) {
            var id = contract.get("id").asText();
            var version = contract.get("version").asInt();
            var base = Names.pascal(id) + "V" + version;
            out.append("\n### `").append(base).append("Task` — task `").append(id).append('@')
                    .append(version).append("`\n");
            if (contract.hasNonNull("description")) {
                out.append(contract.get("description").asText()).append("\n");
            }
            out.append("\n- **Input** (`").append(inputName(contract, base)).append("`): ")
                    .append(attributes(contract.get("input"))).append("\n");
            out.append("- **Output** (`").append(outputName(contract, base)).append("`): ")
                    .append(attributes(contract.get("output"))).append("\n");
            out.append("- **Errors**: ").append(errors(contract.get("errors"))).append("\n");
        }
        return out.toString();
    }

    private static String inputName(JsonNode contract, String base) {
        return has(contract.get("input")) ? base + "Input" : "void";
    }

    private static String outputName(JsonNode contract, String base) {
        return has(contract.get("output")) ? base + "Output" : "void";
    }

    private static String attributes(JsonNode node) {
        if (!has(node)) {
            return "none";
        }
        var parts = new ArrayList<String>();
        node.fields().forEachRemaining(e ->
                parts.add("`" + e.getKey() + "`: " + readableType(e.getValue())));
        return String.join(", ", parts);
    }

    private static String errors(JsonNode errors) {
        if (errors == null || !errors.isArray() || errors.isEmpty()) {
            return "none";
        }
        var parts = new ArrayList<String>();
        for (var error : errors) {
            var code = error.get("code").asText();
            var line = "`" + Names.pascal(code) + "` (" + code + ")";
            if (error.hasNonNull("description")) {
                line += " — " + error.get("description").asText();
            }
            parts.add(line);
        }
        return "throw one to fail the step — " + String.join("; ", parts);
    }

    private static String readableType(JsonNode attribute) {
        var type = attribute.get("type").asText();
        return switch (type) {
            case "string" -> "String";
            case "integer" -> "Long";
            case "number" -> "BigDecimal";
            case "boolean" -> "Boolean";
            case "date" -> "LocalDate";
            case "datetime" -> "LocalDateTime";
            case "object" -> "JsonNode";
            case "array" -> "List<" + (attribute.has("items")
                    ? readableType(attribute.get("items")) : "Object") + ">";
            default -> type;
        };
    }

    private static boolean has(JsonNode node) {
        return node != null && node.isObject() && node.size() > 0;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final Map<String, String> RESOURCES = Map.of(
            "gitignore", "templates/project/gitignore",
            "mvnw", "templates/project/mvnw",
            "mvnw.cmd", "templates/project/mvnw.cmd",
            "maven-wrapper.properties", "templates/project/maven-wrapper.properties");

    private static String resource(String key) {
        var path = RESOURCES.get(key);
        try (var in = ProjectGenerator.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing template resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
