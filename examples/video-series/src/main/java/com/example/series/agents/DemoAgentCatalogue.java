package com.example.series.agents;

import io.mateu.workflow.application.out.WorkflowAgentCatalogue;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * DEMO STAND-IN — the published agent catalogue the engine checks AGENT steps against when it imports a
 * definition (an unknown {@code agent.ref} refuses the definition). In production this is the IA catalogue,
 * read over HTTP with client credentials ({@code workflow.agents.catalogue-url}, {@code token-uri},
 * {@code client-id}, {@code client-secret}); here it is one agent, published by this bean, so the series app
 * needs no catalogue service. The catalogue owns what the definition does not say: the agent's instructions,
 * model, tools and its output schema — never credentials.
 */
@Component
public class DemoAgentCatalogue implements WorkflowAgentCatalogue {

    /** What front-desk-assistant answers: the JSON the demo runtime produces, and checks, for every invocation. */
    public static final Map<String, Object> FRONT_DESK_OUTPUT_SCHEMA = Map.of(
            "type", "object",
            "required", List.of("category", "needsStaff", "actions", "summary"),
            "properties", Map.of(
                    "category", Map.of("type", "string",
                            "enum", List.of("accessibility", "family", "arrival", "celebration", "other")),
                    "needsStaff", Map.of("type", "boolean"),
                    "actions", Map.of("type", "array", "items", Map.of("type", "string")),
                    "summary", Map.of("type", "string")));

    private static final List<Agent> AGENTS = List.of(new Agent(
            "front-desk-assistant",
            "Front desk assistant (demo stand-in)",
            "Reads a guest's special request and lists what the front desk must prepare. A deterministic demo "
                    + "stand-in: rules, not a model.",
            List.of(), List.of(), List.of(),
            FRONT_DESK_OUTPUT_SCHEMA,
            "demo-1"));

    @Override
    public List<Agent> list() {
        return AGENTS;
    }
}
