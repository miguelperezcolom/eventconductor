package io.mateu.workflow.domain.aggregates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.mateu.workflow.application.services.WorkflowDefinitionValidator;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine's half of {@code AiReferenceExamplesTest} (workflow-maven-plugin): every whole workflow
 * definition shown in the Claude Code skills ({@code .claude/skills/**}) and the published AI reference
 * files ({@code doc/public/eventconductor-ai-*.md}) and a set of doc pages must be accepted by the engine as it loads a file —
 * the schema on the document as written, then binding and {@link WorkflowDefinition#checkInvariants()} —
 * so an example cannot teach an assistant something the engine would refuse at import.
 */
class AiReferenceExamplesEngineTest {

    private static final Path REPO = Path.of("..", "..").toAbsolutePath().normalize();
    private static final Pattern FENCE = Pattern.compile(
            "(?m)^(?<indent>[ \\t]*)```(?<lang>json|yaml|yml)[ \\t]*\\n(?<body>.*?)^\\k<indent>```[ \\t]*$",
            Pattern.DOTALL);
    private static final String SKIP_MARKER = "<!-- ec-example: skip -->";

    private final ObjectMapper json = new ObjectMapper();
    private final ObjectMapper yaml = new YAMLMapper();
    private final WorkflowDefinitionValidator validator = new WorkflowDefinitionValidator();

    @TestFactory
    Stream<DynamicTest> everyWholeDefinitionLoadsInTheEngine() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (Path file : aiFiles()) {
            String text = Files.readString(file);
            Matcher m = FENCE.matcher(text);
            while (m.find()) {
                String before = text.substring(Math.max(0, m.start() - 200), m.start());
                if (before.stripTrailing().endsWith(SKIP_MARKER)) {
                    continue;
                }
                int line = (int) text.substring(0, m.start()).chars().filter(c -> c == '\n').count() + 1;
                String indent = m.group("indent");
                String body = m.group("body").lines()
                        .map(l -> l.startsWith(indent) ? l.substring(indent.length()) : l)
                        .reduce("", (a, b) -> a + b + "\n");
                ObjectMapper mapper = "json".equals(m.group("lang")) ? json : yaml;
                JsonNode tree;
                try {
                    tree = mapper.readTree(body);
                } catch (IOException e) {
                    continue; // parse failures are the maven-plugin test's to report
                }
                if (tree == null || !tree.isObject() || !tree.has("steps")) {
                    continue;
                }
                String where = REPO.relativize(file) + ":" + line;
                tests.add(DynamicTest.dynamicTest(where, () -> {
                    validator.validateSource(tree, where);
                    WorkflowDefinition definition = mapper.treeToValue(tree, WorkflowDefinition.class);
                    definition.checkInvariants();
                    assertThat(definition.steps()).as(where).isNotEmpty();
                }));
            }
        }
        assertThat(tests).as("whole workflow definitions in the AI reference files").hasSizeGreaterThan(10);
        return tests.stream();
    }

    /** Doc pages whose examples are checked too (the ones the AI material leans on most). */
    static final List<String> DOC_PAGES = List.of(
            "doc/src/content/docs/guides/http-calls.md",
            "doc/src/content/docs/guides/llm-calls.md",
            "doc/src/content/docs/guides/workers.md",
            "doc/src/content/docs/guides/updating-running-processes.md",
            "doc/src/content/docs/guides/workflow-definitions.md",
            "doc/src/content/docs/guides/testing-workflows.md",
            "doc/src/content/docs/guides/request-response.md",
            "doc/src/content/docs/reference/step-types.md",
            "doc/src/content/docs/reference/maven-plugin.md",
            "doc/src/content/docs/reference/configuration.md");

    private static List<Path> aiFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        Path skills = REPO.resolve(".claude/skills");
        if (Files.isDirectory(skills)) {
            try (Stream<Path> walk = Files.walk(skills)) {
                walk.filter(p -> p.toString().endsWith(".md")).sorted().forEach(files::add);
            }
        }
        try (Stream<Path> list = Files.list(REPO.resolve("doc/public"))) {
            list.filter(p -> p.getFileName().toString().matches("eventconductor-ai-.*\\.md")).sorted().forEach(files::add);
        }
        DOC_PAGES.stream().map(REPO::resolve).forEach(files::add);
        return files;
    }
}
