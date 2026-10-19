package io.mateu.workflow.domain.aggregates;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * What an {@code AGENT_CALL} step asks a model to do, and with which tools. Carried in the {@code .ec}
 * file as the step's {@code agent} block. {@code system} and {@code goal} are text templates the engine
 * renders once, when the step starts; every model turn is a child execution of the step dispatched to
 * the built-in {@code agent-turn} worker task, and every tool the model calls is a child execution of
 * the tool's own step — see {@code AGENT-CALL-PLAN.md}.
 *
 * @param connection      a named connection ({@code workflow.llm.connections.<name>}): provider, model, key
 * @param model           overrides the connection's model
 * @param system          the system prompt (text template)
 * @param goal            the first user message (text template): what the agent must achieve
 * @param temperature     sampling temperature, 0–2; omitted unless set
 * @param tools           the allowlist of tools the model may call
 * @param output          how the final answer is checked ({@code schema}) and kept ({@code mapping})
 * @param maxSteps        the most tool calls over the whole loop — required
 * @param maxTokens       the most input + output tokens over every turn; none when null
 * @param maxDuration     the most wall-clock time for the whole loop (ISO-8601); none when null
 * @param toolConcurrency how many tool calls of one turn run at once; default 1
 * @param onToolError     {@code fail} (default): a tool that fails for good fails the step;
 *                        {@code report}: the error becomes the model's tool result
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentCall(
        String connection,
        String model,
        String system,
        String goal,
        Double temperature,
        List<AgentTool> tools,
        AgentOutput output,
        Integer maxSteps,
        Integer maxTokens,
        String maxDuration,
        Integer toolConcurrency,
        String onToolError
) {

    public static final String FAIL = "fail";
    public static final String REPORT = "report";

    public List<AgentTool> toolList() {
        return tools == null ? List.of() : tools.stream().filter(java.util.Objects::nonNull).toList();
    }

    /** The declared tool called {@code name}, or null: a model naming anything else is refused. */
    public AgentTool tool(String name) {
        return toolList().stream().filter(tool -> tool.name() != null && tool.name().equals(name)).findFirst().orElse(null);
    }

    public int concurrency() {
        return toolConcurrency == null || toolConcurrency < 1 ? 1 : toolConcurrency;
    }

    public int stepBudget() {
        return maxSteps == null || maxSteps < 1 ? 1 : maxSteps;
    }

    public boolean reportsToolErrors() {
        return REPORT.equalsIgnoreCase(onToolError);
    }

    /** The wall-clock budget, or null when none (or unreadable — validation refuses that). */
    public Duration duration() {
        if (maxDuration == null || maxDuration.isBlank()) {
            return null;
        }
        try {
            return Duration.parse(maxDuration.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * One tool the model may call: exactly one of {@code step}, {@code http}, {@code process} and
     * {@code task} says what runs.
     *
     * @param name            what the model calls it ({@code [A-Za-z0-9_-]{1,64}})
     * @param description     what it does, for the model
     * @param step            a template step of this definition, used only as a tool
     * @param http            an HTTP_CALL block (templates see the arguments)
     * @param process         a child workflow definition id
     * @param task            a task contract ({@code <id>@<version>}), run as an ACTION
     * @param topic           for {@code task}: the destination; default the contract's
     * @param outputVariables for {@code process}: the child's variables that are the result
     * @param inputSchema     a JSON Schema (an object schema) the arguments must be valid against
     * @param approval        a person must approve each call first
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentTool(
            String name,
            String description,
            String step,
            HttpCall http,
            String process,
            String task,
            String topic,
            List<String> outputVariables,
            Map<String, Object> inputSchema,
            ToolApproval approval
    ) {

        /** {@code step}, {@code http}, {@code process} or {@code task}; null when none is declared. */
        public String kind() {
            if (step != null && !step.isBlank()) {
                return "step";
            }
            if (http != null) {
                return "http";
            }
            if (process != null && !process.isBlank()) {
                return "process";
            }
            if (task != null && !task.isBlank()) {
                return "task";
            }
            return null;
        }
    }

    /**
     * A person approves each call of a tool before it runs: a USER_TASK on {@code formId} whose
     * {@code approved} field decides (and whose {@code comment}, when the call is rejected, is told to
     * the model).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolApproval(
            String formId,
            String assignee,
            Object candidateUsers,
            Object candidateGroups
    ) {

        public TaskAssignment assignment() {
            if (assignee == null && candidateUsers == null && candidateGroups == null) {
                return null;
            }
            return new TaskAssignment(assignee, candidateUsers, candidateGroups, null, null, null);
        }
    }

    /**
     * The final answer: {@code schema} — a JSON Schema it must be valid against (it is then parsed as
     * JSON); {@code mapping} — process variable → JEXL over {@code {text, json, usage, turns,
     * toolCalls}}; {@code variable} — the variable that receives the text (or the JSON document).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentOutput(
            Map<String, Object> schema,
            Map<String, String> mapping,
            String variable
    ) {
    }
}
