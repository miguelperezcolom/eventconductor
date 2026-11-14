package com.example.series.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.worker.api.TaskFailure;
import io.mateu.workflow.worker.api.TaskRegistration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DEMO STAND-IN — the agent runtime. The engine sends every AGENT step to the generic task {@code run-agent@1}
 * (topic {@code agent-calls}) with one variable, {@code __agent}: the rendered request (agentRef, taskPrompt,
 * input, outputVariable, timeout budget...). In production the IA agent runtime takes it, runs the published agent
 * — model, tools, retrieval — validates the result against the agent's output schema and answers with that one
 * variable. Here a task handler in the same app does the same with rules instead of a model, so a recorded run
 * gives the same answer every time.
 *
 * <p>Embedded, the engine's task dispatcher hands {@code run-agent@1} to this registration like any other task,
 * and the reply names the invocation (the worker runtime echoes it), as the engine requires of an agent's reply.
 */
@Configuration
public class DemoAgentRuntime {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DemoAgentRuntime.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Bean
    @SuppressWarnings({"unchecked", "rawtypes"})
    TaskRegistration<Map<String, Object>, Map<String, Object>> runAgent() {
        Class<Map<String, Object>> map = (Class) Map.class;
        return new TaskRegistration<Map<String, Object>, Map<String, Object>>("run-agent", 1, "agent-calls", map, map,
                (input, context) -> {
                    var request = JSON.readTree(String.valueOf(input.get("__agent")));
                    var outputVariable = request.path("outputVariable").asText();
                    var result = run(request);
                    log.info("Agent {} answered for step {}: {}", request.path("agentRef").asText(), context.stepId(), result);
                    return Map.of(outputVariable, result);
                });
    }

    /** Runs the agent the request names and returns its result, a JSON document valid against its output schema. */
    public static String run(JsonNode request) throws Exception {
        var ref = request.path("agentRef").asText();
        if (!"front-desk-assistant".equals(ref)) {
            throw new TaskFailure("AGENT_UNKNOWN", "The demo runtime does not run agent " + ref, false);
        }
        var result = frontDeskAssistant(request.path("input"), request.path("taskPrompt").asText(""));
        var json = JSON.writeValueAsString(result);
        checkAgainstSchema(result);
        return json;
    }

    /** The demo agent: what the front desk must prepare for a guest's special request. */
    static Map<String, Object> frontDeskAssistant(JsonNode input, String taskPrompt) {
        var guest = input.path("guest").asText("the guest");
        var room = input.path("room").asText("");
        var text = (input.path("request").asText("") + " " + taskPrompt).toLowerCase(Locale.ROOT);
        var forRoom = room.isBlank() ? "" : " in room " + room;
        String category;
        boolean needsStaff;
        List<String> actions = new ArrayList<>();
        if (text.contains("wheelchair") || text.contains("accessib")) {
            category = "accessibility";
            needsStaff = true;
            actions.add("Move " + guest + " to an accessible room on a low floor");
            actions.add("Ask housekeeping to check the grab rails" + forRoom);
        } else if (text.contains("cot") || text.contains("baby")) {
            category = "family";
            needsStaff = true;
            actions.add("Put a cot" + forRoom + " before arrival");
        } else if (text.contains("anniversary") || text.contains("birthday")) {
            category = "celebration";
            needsStaff = true;
            actions.add("Leave a bottle of cava and a card" + forRoom);
        } else if (text.contains("late")) {
            category = "arrival";
            needsStaff = false;
            actions.add("Note the late arrival on the booking");
        } else {
            category = "other";
            needsStaff = false;
            actions.add("Note the request on the booking");
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("category", category);
        result.put("needsStaff", needsStaff);
        result.put("actions", actions);
        result.put("summary", guest + ": " + category + " request" + (needsStaff ? ", staff to act" : ", nothing to prepare"));
        return result;
    }

    /** The runtime's side of the contract: the result must match the agent's published output schema. */
    @SuppressWarnings("unchecked")
    static void checkAgainstSchema(Map<String, Object> result) {
        var schema = DemoAgentCatalogue.FRONT_DESK_OUTPUT_SCHEMA;
        for (var field : (List<String>) schema.get("required")) {
            if (!result.containsKey(field)) {
                throw new TaskFailure("AGENT_INVALID_OUTPUT", "The agent's result has no " + field, false);
            }
        }
        var categories = (List<String>) ((Map<String, Object>) ((Map<String, Object>) schema.get("properties"))
                .get("category")).get("enum");
        if (!categories.contains(result.get("category"))) {
            throw new TaskFailure("AGENT_INVALID_OUTPUT", "Unknown category " + result.get("category"), false);
        }
    }
}
