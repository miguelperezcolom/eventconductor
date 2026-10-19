package io.mateu.workflow.maven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the AI-facing material honest: every JSON/YAML example in the Claude Code skills
 * ({@code .claude/skills/**}) and in the published AI reference files
 * ({@code doc/public/eventconductor-ai-*.md}) is validated with the same {@link SpecValidator}
 * the {@code validate} goal runs, so an example that stops matching the schema or the engine's
 * rules fails the build instead of teaching an assistant something wrong.
 *
 * <p>How a fenced {@code json}/{@code yaml} block is classified:
 * <ul>
 *   <li>an object with {@code steps} — a whole workflow definition: schema and semantic checks;</li>
 *   <li>a step, or a list of steps (objects whose {@code type} is a step type) — a fragment: wrapped in a
 *       definition and checked against the schema (its references may point at steps it does not show);</li>
 *   <li>an object whose keys are all top-level definition fields — merged into a minimal definition
 *       and fully checked;</li>
 *   <li>a list of {@code cron.yaml} / {@code events.yaml} entries, a form, a rule, a task contract —
 *       validated as that kind;</li>
 *   <li>anything else (application properties, REST bodies, ...) is not a definition and is skipped.</li>
 * </ul>
 * A block preceded by {@code <!-- ec-example: skip -->} is skipped on purpose (e.g. a deliberately wrong
 * example in a list of mistakes). Every block that is declared {@code json} must parse as JSON, and
 * every {@code yaml} block as YAML.
 */
class AiReferenceExamplesTest {

    private static final Path REPO = Path.of("..", "..").toAbsolutePath().normalize();
    private static final Pattern FENCE = Pattern.compile(
            "(?m)^(?<indent>[ \\t]*)```(?<lang>json|yaml|yml)[ \\t]*\\n(?<body>.*?)^\\k<indent>```[ \\t]*$",
            Pattern.DOTALL);
    private static final String SKIP_MARKER = "<!-- ec-example: skip -->";

    private final SpecValidator validator = new SpecValidator();
    private final ObjectMapper json = new ObjectMapper();
    private final YAMLMapper yaml = new YAMLMapper();
    private final Set<String> stepTypes = stepTypes();
    private final Set<String> topLevelFields = topLevelFields();

    record Example(Path file, int line, String lang, String body) {
        @Override
        public String toString() {
            return REPO.relativize(file) + ":" + line;
        }
    }

    @TestFactory
    Stream<DynamicTest> everyDefinitionExampleValidates() throws IOException {
        List<Path> files = aiFiles();
        assertThat(files).as("AI reference files under " + REPO).isNotEmpty();
        List<DynamicTest> tests = new ArrayList<>();
        for (Path file : files) {
            for (Example example : examples(file)) {
                tests.add(DynamicTest.dynamicTest(example.toString(), () -> check(example)));
            }
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> theMainFilesCarryValidatedExamples() throws IOException {
        // A guard against the classifier silently skipping everything: these files must keep
        // a minimum of whole, validated workflow definitions.
        return Stream.of(
                        ".claude/skills/eventconductor/SKILL.md",
                        ".claude/skills/eventconductor/reference/patterns.md",
                        "doc/public/eventconductor-ai-compact.md",
                        "doc/public/eventconductor-ai-full.md")
                .map(REPO::resolve)
                .map(file -> DynamicTest.dynamicTest(REPO.relativize(file).toString(), () -> {
                    long whole = 0;
                    for (Example example : examples(file)) {
                        JsonNode node = parse(example);
                        if (node != null && node.isObject() && node.has("steps")) {
                            whole++;
                        }
                    }
                    assertThat(whole).as("whole workflow definitions in " + file).isGreaterThanOrEqualTo(1);
                }));
    }

    private void check(Example example) throws Exception {
        JsonNode node = parse(example);
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        List<String> violations = switch (classify(node)) {
            case WORKFLOW -> validator.validate(SpecValidator.Kind.WORKFLOW, node);
            case STEPS -> schemaOnly(wrapSteps(node));
            case DEFINITION_FIELDS -> validator.validate(SpecValidator.Kind.WORKFLOW, wrapFields((ObjectNode) node));
            case CRON -> validator.validate(SpecValidator.Kind.CRON, node);
            case EVENTS -> validator.validate(SpecValidator.Kind.EVENTS, node);
            case FORM -> validator.validate(SpecValidator.Kind.FORM, node);
            case RULE -> validator.validate(SpecValidator.Kind.RULE, node);
            case TASK -> validator.validate(SpecValidator.Kind.TASK, node);
            case OTHER -> List.of();
        };
        assertThat(violations).as("violations of the example at " + example + ":\n" + example.body()).isEmpty();
    }

    private JsonNode parse(Example example) throws IOException {
        String body = example.body();
        if (body.isBlank()) {
            return null;
        }
        try {
            return "json".equals(example.lang()) ? json.readTree(body) : yaml.readTree(body);
        } catch (IOException e) {
            throw new AssertionError("The " + example.lang() + " example at " + example
                    + " does not parse: " + e.getMessage() + "\n" + body, e);
        }
    }

    enum Kind { WORKFLOW, STEPS, DEFINITION_FIELDS, CRON, EVENTS, FORM, RULE, TASK, OTHER }

    private Kind classify(JsonNode node) {
        if (node.isObject()) {
            if (node.has("steps")) {
                return Kind.WORKFLOW;
            }
            if (isStep(node)) {
                return Kind.STEPS;
            }
            if (node.has("fields") && node.has("name")) {
                return Kind.FORM;
            }
            if (node.has("group") && node.has("id") && node.has("version")) {
                return Kind.TASK;
            }
            if (node.has("type") && (node.has("when") || node.has("rows"))) {
                return Kind.RULE;
            }
            if (node.has("triggers") && node.size() == 1 && node.get("triggers").isArray()
                    && allHave(node.get("triggers"), "workflow")) {
                return Kind.EVENTS;
            }
            if (node.has("schedules") && node.size() == 1 && node.get("schedules").isArray()
                    && allHave(node.get("schedules"), "workflow")) {
                return Kind.CRON;
            }
            if (node.size() > 0 && fieldNames(node).stream().allMatch(topLevelFields::contains)) {
                return Kind.DEFINITION_FIELDS;
            }
            return Kind.OTHER;
        }
        if (node.isArray() && !node.isEmpty()) {
            boolean allSteps = true;
            for (JsonNode element : node) {
                allSteps &= isStep(element);
            }
            if (allSteps) {
                return Kind.STEPS;
            }
            if (allHave(node, "workflow") && allHave(node, "cron")) {
                return Kind.CRON;
            }
            if (allHave(node, "workflow") && allHave(node, "destination")) {
                return Kind.EVENTS;
            }
        }
        return Kind.OTHER;
    }

    private boolean isStep(JsonNode node) {
        return node.isObject() && node.has("id") && node.path("type").isTextual()
                && stepTypes.contains(node.path("type").asText());
    }

    private static boolean allHave(JsonNode array, String field) {
        for (JsonNode element : array) {
            if (!element.isObject() || !element.has(field)) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private ObjectNode wrapSteps(JsonNode fragment) {
        ObjectNode wf = json.createObjectNode().put("id", "example").put("name", "Example");
        ArrayNode steps = wf.putArray("steps");
        if (fragment.isArray()) {
            fragment.forEach(steps::add);
        } else {
            steps.add(fragment);
        }
        return wf;
    }

    private ObjectNode wrapFields(ObjectNode fields) {
        ObjectNode wf = json.createObjectNode().put("id", "example").put("name", "Example");
        ArrayNode steps = wf.putArray("steps");
        steps.addObject().put("id", "start").put("type", "START").put("name", "Start");
        steps.addObject().put("id", "end").put("type", "END").put("name", "End").put("preconditionStepId", "start");
        Iterator<String> names = fields.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            wf.set(name, fields.get(name));
        }
        return wf;
    }

    /** Schema violations only: they are reported with the JSON path of the offending node. */
    private List<String> schemaOnly(ObjectNode wf) {
        return validator.validate(SpecValidator.Kind.WORKFLOW, wf).stream()
                .filter(violation -> violation.startsWith("$") || violation.startsWith("/"))
                .toList();
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

    static List<Example> examples(Path file) throws IOException {
        String text = Files.readString(file);
        List<Example> examples = new ArrayList<>();
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
            examples.add(new Example(file, line, m.group("lang").replace("yml", "yaml"), body));
        }
        return examples;
    }

    private Set<String> stepTypes() {
        JsonNode schema = engineSchema();
        Set<String> types = new HashSet<>();
        schema.path("$defs").path("Step").path("properties").path("type").path("enum")
                .forEach(t -> types.add(t.asText()));
        types.add("MESSAGE"); // legacy alias of WAIT_FOR_MESSAGE, still read
        assertThat(types).as("step types from the schema").contains("START", "ACTION", "END");
        return types;
    }

    private Set<String> topLevelFields() {
        Set<String> fields = new HashSet<>();
        engineSchema().path("properties").fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private JsonNode engineSchema() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("schemas/workflow-definition-schema.json")) {
            assertThat(in).as("bundled workflow schema").isNotNull();
            return json.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
