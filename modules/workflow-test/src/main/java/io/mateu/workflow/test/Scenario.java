package io.mateu.workflow.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * A test case written as data: the process to start, how its tasks, HTTP calls, model calls and agents
 * answer, what happens next (time passing, messages, a person completing a form) and what it must end
 * like. A scenario file (YAML or JSON) lets someone who does not write Java add a case:
 *
 * <pre>{@code
 * workflow: rooms-booking-safe            # for every scenario of the file, unless one says otherwise
 * scenarios:
 *   - name: a sold-out room undoes the others
 *     variables:
 *       guest: Bob
 *       rooms: [{number: 101}, {number: 900}]
 *     steps:                              # answers by step id (tasks: by task id or topic)
 *       register-booking: {complete: {bookingNumber: BK-2}}
 *       create-room-booking:              # the first answer whose `when` holds
 *         - {when: "room.number == 900", fail: SOLD_OUT, retryable: false}
 *         - {complete: {roomBookingId: "RB-${room.number}"}}
 *       cancel-room-booking: {complete: {}}
 *     expect:
 *       status: COMPENSATED
 *       passedSteps: [register-booking, cancel-room-booking]
 *       notPassedSteps: [notify-guest]
 * }</pre>
 *
 * Run them with {@link ScenarioSource} on a {@code @ParameterizedTest}, or with
 * {@link WorkflowTestRuntime#scenarios(String...)} from a {@code @TestFactory}. The format is described
 * in full in the testing guide.
 */
public final class Scenario {

    static final ObjectMapper JSON = Values.JSON;
    static final YAMLMapper YAML = new YAMLMapper();
    private static final List<String> EXTENSIONS = List.of(".yaml", ".yml", ".json");

    private final String name;
    private final String source;
    private final ObjectNode spec;

    Scenario(String name, String source, ObjectNode spec) {
        this.name = name;
        this.source = source;
        this.spec = spec;
    }

    /** What the scenario is called: its {@code name}, or its position in the file. */
    public String name() {
        return name;
    }

    /** The file it was read from. */
    public String source() {
        return source;
    }

    /** The definition it starts. */
    public String workflow() {
        return spec.path("workflow").asText(null);
    }

    /** The scenario as written (with the file's defaults applied). */
    public JsonNode spec() {
        return spec.deepCopy();
    }

    @Override
    public String toString() {
        return name + " (" + source + ")";
    }

    // ---------------------------------------------------------------- loading

    /**
     * Every scenario under {@code locations}: scenario files, or directories of them (searched recursively for
     * {@code .yaml}, {@code .yml} and {@code .json}), on the file system (relative to the working directory)
     * or on the classpath ({@code classpath:scenarios/}). In file order, then in the order of each file.
     */
    public static List<Scenario> load(String... locations) {
        var scenarios = new ArrayList<Scenario>();
        for (var location : locations) {
            for (var file : resolve(location)) {
                scenarios.addAll(read(file.text(), file.name()));
            }
        }
        if (scenarios.isEmpty()) {
            throw new IllegalArgumentException("No scenarios found in " + String.join(", ", locations));
        }
        return scenarios;
    }

    /**
     * The scenarios of one document: an object with {@code scenarios} (and the defaults its scenarios share —
     * {@code workflow}, {@code variables} …), a list of scenarios, or a single scenario.
     */
    public static List<Scenario> parse(String text, String source) {
        return read(text, source);
    }

    private record SourceFile(String name, String text) {
    }

    private static List<Scenario> read(String text, String source) {
        JsonNode root;
        try {
            root = (source.toLowerCase(Locale.ROOT).endsWith(".json") ? JSON : YAML).readTree(text);
        } catch (IOException e) {
            throw new IllegalArgumentException(source + " is not a scenario file: " + e.getMessage(), e);
        }
        var defaults = JSON.createObjectNode();
        JsonNode list;
        if (root != null && root.isObject() && root.has("scenarios")) {
            root.properties().forEach(entry -> {
                if (!"scenarios".equals(entry.getKey())) {
                    defaults.set(entry.getKey(), entry.getValue());
                }
            });
            list = root.get("scenarios");
        } else if (root != null && root.isArray()) {
            list = root;
        } else if (root != null && root.isObject()) {
            list = JSON.createArrayNode().add(root);
        } else {
            throw new IllegalArgumentException(source + " holds no scenario");
        }
        if (!list.isArray()) {
            throw new IllegalArgumentException(source + ": 'scenarios' must be a list");
        }
        var scenarios = new ArrayList<Scenario>();
        var index = 0;
        for (var item : list) {
            index++;
            if (!item.isObject()) {
                throw new IllegalArgumentException(source + ": scenario " + index + " is not an object");
            }
            var spec = defaults.deepCopy();
            // The file's maps (variables, tasks, steps, http …) are merged one level deep: a scenario adds to
            // them and overrides an entry by its key. Anything else it states replaces the file's.
            item.properties().forEach(entry -> {
                var shared = spec.get(entry.getKey());
                if (shared != null && shared.isObject() && entry.getValue().isObject()) {
                    var merged = ((ObjectNode) shared).deepCopy();
                    entry.getValue().properties().forEach(inner -> merged.set(inner.getKey(), inner.getValue()));
                    spec.set(entry.getKey(), merged);
                } else {
                    spec.set(entry.getKey(), entry.getValue());
                }
            });
            var name = spec.hasNonNull("name") ? spec.get("name").asText() : "scenario " + index;
            if (!spec.hasNonNull("workflow") || spec.get("workflow").asText().isBlank()) {
                throw new IllegalArgumentException(source + ": scenario '" + name + "' names no workflow to start");
            }
            scenarios.add(new Scenario(name, source, spec));
        }
        return scenarios;
    }

    private static List<SourceFile> resolve(String location) {
        try {
            if (location.startsWith("classpath:") || location.startsWith("classpath*:")) {
                return fromClasspath(location.substring(location.indexOf(':') + 1));
            }
            var path = Path.of(location);
            if (Files.isRegularFile(path)) {
                return List.of(new SourceFile(path.toString(), Files.readString(path)));
            }
            if (!Files.isDirectory(path)) {
                throw new IllegalArgumentException("No scenario file or directory at " + path.toAbsolutePath());
            }
            try (Stream<Path> walk = Files.walk(path)) {
                var files = new ArrayList<SourceFile>();
                for (var file : walk.filter(Files::isRegularFile).filter(Scenario::isScenarioFile).sorted().toList()) {
                    files.add(new SourceFile(file.toString(), Files.readString(file)));
                }
                return files;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read scenarios from " + location, e);
        }
    }

    private static List<SourceFile> fromClasspath(String location) throws IOException {
        var resolver = new PathMatchingResourcePatternResolver(Scenario.class.getClassLoader());
        var trimmed = location.startsWith("/") ? location.substring(1) : location;
        var files = new ArrayList<SourceFile>();
        if (EXTENSIONS.stream().anyMatch(trimmed.toLowerCase(Locale.ROOT)::endsWith)) {
            var resource = resolver.getResource("classpath:" + trimmed);
            if (!resource.exists()) {
                throw new IllegalArgumentException("No scenario file at classpath:" + trimmed);
            }
            files.add(new SourceFile("classpath:" + trimmed, text(resource)));
            return files;
        }
        var directory = trimmed.isEmpty() || trimmed.endsWith("/") ? trimmed : trimmed + "/";
        var resources = new LinkedHashMap<String, Resource>();
        for (var extension : EXTENSIONS) {
            for (var resource : resolver.getResources("classpath*:" + directory + "**/*" + extension)) {
                resources.putIfAbsent(resource.getURL().toString(), resource);
            }
        }
        for (var entry : new java.util.TreeMap<>(resources).entrySet()) {
            var url = entry.getKey();
            var relative = url.contains(directory) && !directory.isEmpty()
                    ? url.substring(url.lastIndexOf(directory) + directory.length()) : entry.getValue().getFilename();
            files.add(new SourceFile("classpath:" + directory + relative, text(entry.getValue())));
        }
        return files;
    }

    private static String text(Resource resource) throws IOException {
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static boolean isScenarioFile(Path path) {
        var name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(name::endsWith);
    }
}
