package io.mateu.workflow.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The docs' forEach + inline-body examples, read from the pages themselves and run: what an idempotency
 * key built from {@code ${businessKey}} and {@code ${room.number}} renders to in each room's child —
 * the booking's key and the room's number, so the hotel books each room once.
 */
@EventConductorTest
class DocInlineBodyExamplesTest {

    private static final Path DOCS = Path.of("..", "..", "doc", "src", "content", "docs", "guides");
    private static final Pattern YAML = Pattern.compile("(?ms)^```yaml\\n(.*?)^```");
    private static final ObjectMapper MAPPER = new YAMLMapper();
    private static final List<Map<String, Object>> ROOMS = List.of(Map.of("number", 101), Map.of("number", 102));

    @Test
    void theWhyAProcessEnginePageBooksEachRoomUnderTheBookingsKey(WorkflowTestRuntime runtime, @org.junit.jupiter.api.io.TempDir Path dir)
            throws IOException {
        // The page's first YAML block: the steps of the hotel booking.
        var steps = (ArrayNode) MAPPER.readTree(firstYaml("why-a-process-engine.mdx", "id: rooms"));
        deploy(runtime, dir, "why-booking", steps);
        var create = runtime.whenHttpCall("create").thenRespond(201, "{\"id\":\"HB-1\"}");
        runtime.whenStep("confirm").thenComplete();

        var booking = runtime.start("why-booking", "BOOKING-123", Map.of("rooms", ROOMS));
        runtime.completeUserTask(booking, "approve", Map.of());

        assertThat(booking).isCompleted();
        assertThat(create.requests()).extracting(request -> header(request, "Idempotency-Key"))
                .containsExactlyInAnyOrder("BOOKING-123-101", "BOOKING-123-102");
    }

    @Test
    void theHttpCallsIdempotencyExampleWorksInsideAnInlineBody(WorkflowTestRuntime runtime, @org.junit.jupiter.api.io.TempDir Path dir)
            throws IOException {
        // "Not creating duplicates in the target system", pattern 1: the create step, run once per room.
        var create = (ObjectNode) MAPPER.readTree(firstYaml("http-calls.md", "Idempotency-Key: \"${businessKey}-${room.number}\"")).get(0);
        create.put("preconditionStepId", "start");
        var body = MAPPER.createArrayNode();
        body.add(step("start", "START")).add(create).add(step("end", "END").put("preconditionStepId", "create"));
        var rooms = step("rooms", "PROCESS").put("preconditionStepId", "start");
        rooms.putObject("forEach").put("items", "rooms").put("as", "room");
        rooms.set("steps", body);
        var steps = MAPPER.createArrayNode();
        steps.add(step("start", "START")).add(rooms).add(step("end", "END").put("preconditionStepId", "rooms"));
        deploy(runtime, dir, "doc-idempotency", steps);
        var hotel = runtime.whenHttpCall("create").thenRespond(201, "{\"id\":\"HB-1\"}");

        var booking = runtime.start("doc-idempotency", "BOOKING-7", Map.of("rooms", ROOMS));

        assertThat(booking).isCompleted();
        assertThat(hotel.requests()).extracting(request -> header(request, "Idempotency-Key"))
                .containsExactlyInAnyOrder("BOOKING-7-101", "BOOKING-7-102");
    }

    private static ObjectNode step(String id, String type) {
        return MAPPER.createObjectNode().put("id", id).put("type", type).put("name", id);
    }

    private static void deploy(WorkflowTestRuntime runtime, Path dir, String id, JsonNode steps) throws IOException {
        var definition = MAPPER.createObjectNode().put("id", id).put("name", id).put("version", 1);
        definition.set("steps", steps);
        var file = dir.resolve(id + ".json");
        Files.writeString(file, new ObjectMapper().writeValueAsString(definition));
        runtime.deploy(file);
    }

    /** The first ```yaml block of a docs page that contains {@code marker}. */
    private static String firstYaml(String page, String marker) throws IOException {
        var matcher = YAML.matcher(Files.readString(DOCS.resolve(page)));
        while (matcher.find()) {
            if (matcher.group(1).contains(marker)) {
                return matcher.group(1);
            }
        }
        throw new AssertionError("No yaml block with '" + marker + "' in " + page);
    }

    @SuppressWarnings("unchecked")
    private static String header(Map<String, Object> request, String name) {
        return String.valueOf(((Map<String, Object>) request.get("headers")).get(name));
    }
}
