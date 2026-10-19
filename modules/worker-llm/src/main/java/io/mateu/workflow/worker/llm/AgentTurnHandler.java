package io.mateu.workflow.worker.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.mateu.workflow.worker.api.TaskContext;
import io.mateu.workflow.worker.api.TaskFailure;
import io.mateu.workflow.worker.api.TaskHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The built-in {@code agent-turn} task: one model turn of an AGENT_CALL step. The engine sends the
 * conversation so far and the tool specs ({@code __agent}); this asks the model, with the tools declared
 * but <em>never executed here</em> — Spring AI's chat models do not run tools themselves, and the engine
 * runs each call the model asks for as a step execution of its own. The answer goes back as
 * {@code __agentAnswer}: {@code {text, json, toolCalls: [{id, name, arguments}], model, finishReason,
 * usage}}.
 *
 * <ul>
 *   <li>A final answer (no tool calls) of a step with an output {@code schema} must be one JSON
 *       document valid against it; otherwise the turn fails {@code LLM_INVALID_OUTPUT}, retryable.</li>
 *   <li>Provider failures are classified as for {@code llm-call}: a client error is final, 408/409/429,
 *       5xx and I/O are retried per the step's {@code retries}.</li>
 *   <li>Metrics: the {@code llm-call} timer and token counter, tagged with the agent's step.</li>
 * </ul>
 *
 * <p>Prompts, tool arguments and answers are never logged — they carry the process's data.
 */
public class AgentTurnHandler implements TaskHandler<AgentTurnHandler.Input, Map<String, Object>> {

    /** The task variable the engine renders the turn into. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Input(JsonNode __agent) {
    }

    public static final String ANSWER_VARIABLE = "__agentAnswer";

    private static final Logger log = LoggerFactory.getLogger(AgentTurnHandler.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_REPORTED_ERRORS = 5;

    private final LlmWorkerProperties properties;
    private final ChatModelFactory factory;
    private final LlmCallMetrics metrics;
    private final Map<String, ChatModel> models = new ConcurrentHashMap<>();
    private final Map<String, Schema> schemas = new ConcurrentHashMap<>();
    private final SchemaRegistry schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    public AgentTurnHandler(LlmWorkerProperties properties, ChatModelFactory factory, LlmCallMetrics metrics) {
        this.properties = properties;
        this.factory = factory;
        this.metrics = metrics == null ? LlmCallMetrics.NONE : metrics;
    }

    @Override
    public Map<String, Object> handle(Input input, TaskContext context) throws Exception {
        var request = input == null ? null : input.__agent();
        if (request == null || !request.isObject()) {
            throw new TaskFailure("LLM_REQUEST", "the task carries no agent turn", false);
        }
        var connectionName = text(request, "connection");
        var connection = connectionName == null ? null : properties.getConnections().get(connectionName);
        if (connection == null) {
            throw new TaskFailure("LLM_CONFIG", "connection '" + connectionName + "' is not configured"
                    + " (workflow.llm.connections." + connectionName + ")", false);
        }
        var model = models.computeIfAbsent(connectionName, name -> factory.create(name, connection));
        var modelName = text(request, "model") != null && !text(request, "model").isBlank()
                ? text(request, "model") : connection.getModel();
        var call = new LlmCallMetrics.Call(context == null ? null : context.workflowDefinitionId(),
                context == null ? null : context.stepId(), connectionName, modelName);
        var responseSchema = request.path("responseSchema");
        var schemaJson = responseSchema.isObject() && !responseSchema.isEmpty() ? responseSchema.toString() : null;

        var prompt = new Prompt(messages(request, schemaJson), options(model, request, modelName));
        var started = System.nanoTime();
        ChatResponse response;
        try {
            response = model.call(prompt);
        } catch (RuntimeException e) {
            metrics.call(call, LlmCallMetrics.ERROR, System.nanoTime() - started);
            var failure = LlmCallHandler.failureOf(e, connectionName);
            log.info("AGENT_CALL turn {} on {} failed in {} ms: {} (step {})", modelName, connectionName,
                    (System.nanoTime() - started) / 1_000_000, failure.code(), call.step());
            throw failure;
        }
        var elapsed = System.nanoTime() - started;
        var answer = answerOf(response, modelName);
        metrics.tokens(call, "input", answer.inputTokens());
        metrics.tokens(call, "output", answer.outputTokens());
        Object json = null;
        if (answer.toolCalls().isEmpty() && schemaJson != null) {
            try {
                var document = LlmCallHandler.extractJson(answer.text());
                var errors = schemaFor(schemaJson).validate(document, InputFormat.JSON);
                if (!errors.isEmpty()) {
                    throw new TaskFailure("LLM_INVALID_OUTPUT", "the final answer does not match the output schema — "
                            + String.join("; ", errors.stream().limit(MAX_REPORTED_ERRORS)
                            .map(error -> error.getInstanceLocation() + ": " + error.getMessage()).toList()), true);
                }
                json = JSON.readValue(document, Object.class);
            } catch (TaskFailure invalid) {
                metrics.call(call, LlmCallMetrics.INVALID_OUTPUT, elapsed);
                throw invalid;
            }
        }
        metrics.call(call, LlmCallMetrics.SUCCESS, elapsed);
        log.info("AGENT_CALL turn {} on {}: {} ms, {} in / {} out tokens, {} tool calls, finish {} (step {})", answer.model(),
                connectionName, elapsed / 1_000_000, answer.inputTokens(), answer.outputTokens(), answer.toolCalls().size(),
                answer.finishReason(), call.step());
        var result = new LinkedHashMap<String, Object>();
        result.put("text", answer.text());
        if (json != null) {
            result.put("json", json);
        }
        result.put("toolCalls", answer.toolCalls());
        result.put("model", answer.model());
        result.put("finishReason", answer.finishReason());
        var usage = new LinkedHashMap<String, Object>();
        usage.put("inputTokens", answer.inputTokens() == null ? 0 : answer.inputTokens());
        usage.put("outputTokens", answer.outputTokens() == null ? 0 : answer.outputTokens());
        result.put("usage", usage);
        return Map.of(ANSWER_VARIABLE, result);
    }

    /** The conversation as Spring AI messages: system, the goal, and each turn with its tool results. */
    static List<Message> messages(JsonNode request, String schemaJson) {
        var messages = new ArrayList<Message>();
        var system = systemPrompt(text(request, "system"), schemaJson);
        if (system != null) {
            messages.add(new SystemMessage(system));
        }
        for (var message : request.path("messages")) {
            switch (message.path("role").asText()) {
                case "user" -> messages.add(new UserMessage(message.path("content").asText("")));
                case "assistant" -> {
                    var calls = new ArrayList<AssistantMessage.ToolCall>();
                    for (var call : message.path("toolCalls")) {
                        calls.add(new AssistantMessage.ToolCall(call.path("id").asText(), "function",
                                call.path("name").asText(), call.path("arguments").isTextual()
                                ? call.path("arguments").asText() : call.path("arguments").toString()));
                    }
                    messages.add(AssistantMessage.builder().content(message.path("content").asText(""))
                            .toolCalls(calls).build());
                }
                case "tool" -> {
                    var responses = new ArrayList<ToolResponseMessage.ToolResponse>();
                    for (var result : message.path("results")) {
                        responses.add(new ToolResponseMessage.ToolResponse(result.path("id").asText(),
                                result.path("name").asText(), result.path("content").asText("")));
                    }
                    messages.add(ToolResponseMessage.builder().responses(responses).build());
                }
                default -> {
                    // unknown roles are not the engine's; ignore them rather than fail the turn
                }
            }
        }
        return messages;
    }

    static String systemPrompt(String system, String schemaJson) {
        if (schemaJson == null) {
            return system == null || system.isBlank() ? null : system;
        }
        var instruction = "When you give your final answer (no more tool calls), answer with exactly one JSON"
                + " document that is valid against this JSON Schema, and nothing else — no prose, no Markdown"
                + " code fence:\n" + schemaJson;
        return system == null || system.isBlank() ? instruction : system + "\n\n" + instruction;
    }

    private static ChatOptions options(ChatModel model, JsonNode request, String modelName) {
        var defaults = model.getDefaultOptions();
        ChatOptions.Builder<?> builder = defaults == null ? null : defaults.mutate();
        if (!(builder instanceof ToolCallingChatOptions.Builder<?>)) {
            // A model whose options cannot carry tools (a plain ChatModel): the tools must not be dropped.
            var tools = ToolCallingChatOptions.builder();
            if (defaults != null) {
                tools.model(defaults.getModel()).maxTokens(defaults.getMaxTokens()).temperature(defaults.getTemperature());
            }
            builder = tools;
        }
        if (modelName != null) {
            builder.model(modelName);
        }
        if (request.path("temperature").isNumber()) {
            builder.temperature(request.path("temperature").asDouble());
        }
        if (builder instanceof ToolCallingChatOptions.Builder<?> tools) {
            tools.toolCallbacks(toolCallbacks(request.path("tools")));
        }
        return builder.build();
    }

    /** The tools as Spring AI callbacks that declare them — and refuse to run: the engine runs every call. */
    static List<ToolCallback> toolCallbacks(JsonNode tools) {
        var callbacks = new ArrayList<ToolCallback>();
        for (var tool : tools) {
            var definition = ToolDefinition.builder()
                    .name(tool.path("name").asText())
                    .description(tool.path("description").asText(""))
                    .inputSchema(tool.path("inputSchema").isObject() ? tool.path("inputSchema").toString()
                            : "{\"type\":\"object\"}")
                    .build();
            callbacks.add(new ToolCallback() {
                @Override
                public ToolDefinition getToolDefinition() {
                    return definition;
                }

                @Override
                public String call(String toolInput) {
                    throw new IllegalStateException("Tool " + definition.name()
                            + " is run by the engine as a step execution, never by the model client.");
                }
            });
        }
        return callbacks;
    }

    record Answer(String text, List<Map<String, Object>> toolCalls, String model, String finishReason,
                  Integer inputTokens, Integer outputTokens) {
    }

    /** Everything the model said and asked for, across the generations a provider may split it into. */
    static Answer answerOf(ChatResponse response, String requestedModel) {
        var text = new StringBuilder();
        var calls = new ArrayList<Map<String, Object>>();
        String finishReason = null;
        if (response != null && response.getResults() != null) {
            for (var generation : response.getResults()) {
                if (generation == null || generation.getOutput() == null) {
                    continue;
                }
                var output = generation.getOutput();
                if (output.getText() != null && !output.getText().isBlank()) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(output.getText());
                }
                if (output.getToolCalls() != null) {
                    for (var call : output.getToolCalls()) {
                        var map = new LinkedHashMap<String, Object>();
                        map.put("id", call.id());
                        map.put("name", call.name());
                        map.put("arguments", call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments());
                        calls.add(map);
                    }
                }
                if (generation.getMetadata() != null && generation.getMetadata().getFinishReason() != null) {
                    finishReason = generation.getMetadata().getFinishReason();
                }
            }
        }
        var metadata = response == null ? null : response.getMetadata();
        var usage = metadata == null ? null : metadata.getUsage();
        var model = metadata != null && metadata.getModel() != null && !metadata.getModel().isBlank()
                ? metadata.getModel() : requestedModel;
        return new Answer(text.toString(), calls, model, finishReason,
                usage == null ? null : usage.getPromptTokens(), usage == null ? null : usage.getCompletionTokens());
    }

    private Schema schemaFor(String schemaJson) {
        return schemas.computeIfAbsent(schemaJson, json -> {
            try {
                var schema = schemaRegistry.getSchema(json, InputFormat.JSON);
                schema.initializeValidators();
                return schema;
            } catch (RuntimeException e) {
                throw new TaskFailure("LLM_CONFIG", "the output schema is not a valid JSON Schema: " + e.getMessage(), false);
            }
        });
    }

    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
