package io.mateu.workflow.worker.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.mateu.workflow.worker.api.TaskFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The agent-turn task: the conversation the engine sends becomes Spring AI messages, the tools are
 * declared but never run, and what the model asked for comes back as tool calls — against a stub model,
 * and against Anthropic's and OpenAI's real clients talking to a mocked HTTP endpoint, so the
 * provider-specific shapes ({@code tool_use}/{@code tool_result}, {@code tool_calls}/{@code tool}) are the
 * ones a provider would see.
 */
class AgentTurnHandlerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String REQUEST = """
            {"connection": "c", "system": "Be brief.", "temperature": 0.5,
             "tools": [{"name": "lookupOrder", "description": "Look an order up",
                        "inputSchema": {"type": "object", "required": ["orderId"], "properties": {"orderId": {"type": "string"}}}}],
             "messages": [
               {"role": "user", "content": "Where is O-1?"},
               {"role": "assistant", "content": "Let me check.", "toolCalls": [{"id": "toolu_1", "name": "lookupOrder", "arguments": "{\\"orderId\\":\\"O-1\\"}"}]},
               {"role": "tool", "results": [{"id": "toolu_1", "name": "lookupOrder", "content": "{\\"status\\":\\"SHIPPED\\"}"}]}
             ]}
            """;

    final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    volatile Function<Prompt, ChatResponse> answer;
    LlmWorkerProperties properties;
    SimpleMeterRegistry meters;
    AgentTurnHandler handler;
    HttpServer server;
    final List<String> bodies = new CopyOnWriteArrayList<>();
    final List<String> paths = new CopyOnWriteArrayList<>();
    volatile String reply;

    @BeforeEach
    void setUp() {
        properties = new LlmWorkerProperties();
        var connection = new LlmWorkerProperties.Connection();
        connection.setApiKey("k");
        connection.setModel("m-1");
        properties.getConnections().put("c", connection);
        meters = new SimpleMeterRegistry();
        ChatModel model = prompt -> {
            prompts.add(prompt);
            return answer.apply(prompt);
        };
        handler = new AgentTurnHandler(properties, (name, c) -> model, new LlmCallMetrics(meters));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static AgentTurnHandler.Input input(String json) throws IOException {
        return new AgentTurnHandler.Input(JSON.readTree(json));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> answerOf(Map<String, Object> result) {
        return (Map<String, Object>) result.get(AgentTurnHandler.ANSWER_VARIABLE);
    }

    private static ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)),
                ChatResponseMetadata.builder().model("m-1").usage(new DefaultUsage(30, 8, 38, null)).build());
    }

    @Test
    void theConversationBecomesMessagesTheToolsAreDeclaredAndTheCallsComeBack() throws Exception {
        answer = prompt -> response(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("toolu_2", "function", "lookupOrder", "{\"orderId\":\"O-2\"}")))
                .build());

        var result = answerOf(handler.handle(input(REQUEST), null));

        var prompt = prompts.getFirst();
        assertThat(prompt.getInstructions()).extracting(m -> m.getMessageType())
                .containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL);
        var assistant = (AssistantMessage) prompt.getInstructions().get(2);
        assertThat(assistant.getToolCalls()).containsExactly(
                new AssistantMessage.ToolCall("toolu_1", "function", "lookupOrder", "{\"orderId\":\"O-1\"}"));
        var tool = (ToolResponseMessage) prompt.getInstructions().get(3);
        assertThat(tool.getResponses()).containsExactly(
                new ToolResponseMessage.ToolResponse("toolu_1", "lookupOrder", "{\"status\":\"SHIPPED\"}"));
        var options = (ToolCallingChatOptions) prompt.getOptions();
        assertThat(options.getToolCallbacks()).hasSize(1);
        var definition = options.getToolCallbacks().getFirst().getToolDefinition();
        assertThat(definition.name()).isEqualTo("lookupOrder");
        assertThat(definition.inputSchema()).contains("orderId");
        assertThatThrownBy(() -> options.getToolCallbacks().getFirst().call("{}"))
                .hasMessageContaining("run by the engine");
        assertThat(options.getTemperature()).isEqualTo(0.5);

        assertThat(result.get("toolCalls")).isEqualTo(List.of(Map.of("id", "toolu_2", "name", "lookupOrder",
                "arguments", "{\"orderId\":\"O-2\"}")));
        assertThat(result.get("usage")).isEqualTo(Map.of("inputTokens", 30, "outputTokens", 8));
        assertThat(meters.get(LlmCallMetrics.TOKENS).tag("type", "input").counter().count()).isEqualTo(30);
    }

    @Test
    void aFinalAnswerIsCheckedAgainstTheOutputSchema() throws Exception {
        var request = REQUEST.replace("\"temperature\": 0.5,",
                "\"responseSchema\": {\"type\": \"object\", \"required\": [\"decision\"]},");
        answer = prompt -> response(new AssistantMessage("```json\n{\"decision\":\"refund\"}\n```"));
        var result = answerOf(handler.handle(input(request), null));
        assertThat(result.get("json")).isEqualTo(Map.of("decision", "refund"));
        assertThat(prompts.getLast().getInstructions().getFirst().getText()).contains("final answer").contains("decision");

        answer = prompt -> response(new AssistantMessage("{\"other\":1}"));
        assertThatThrownBy(() -> handler.handle(input(request), null))
                .isInstanceOfSatisfying(TaskFailure.class, f -> {
                    assertThat(f.code()).isEqualTo("LLM_INVALID_OUTPUT");
                    assertThat(f.retryable()).isTrue();
                });
        // A turn that asks for tools is not a final answer: the schema does not apply yet.
        answer = prompt -> response(AssistantMessage.builder().content("checking")
                .toolCalls(List.of(new AssistantMessage.ToolCall("t", "function", "lookupOrder", ""))).build());
        assertThat(answerOf(handler.handle(input(request), null)).get("toolCalls"))
                .isEqualTo(List.of(Map.of("id", "t", "name", "lookupOrder", "arguments", "{}")));
    }

    @Test
    void missingRequestsConnectionsAndProviderErrorsAreClassified() throws Exception {
        assertThatThrownBy(() -> handler.handle(new AgentTurnHandler.Input(null), null))
                .isInstanceOfSatisfying(TaskFailure.class, f -> assertThat(f.code()).isEqualTo("LLM_REQUEST"));
        assertThatThrownBy(() -> handler.handle(input("{\"connection\":\"nope\",\"messages\":[]}"), null))
                .isInstanceOfSatisfying(TaskFailure.class, f -> assertThat(f.code()).isEqualTo("LLM_CONFIG"));
        answer = prompt -> {
            throw new RuntimeException(new java.net.SocketTimeoutException("slow"));
        };
        assertThatThrownBy(() -> handler.handle(input(REQUEST), null))
                .isInstanceOfSatisfying(TaskFailure.class, f -> {
                    assertThat(f.code()).isEqualTo("LLM_IO");
                    assertThat(f.retryable()).isTrue();
                });
        answer = prompt -> response(new AssistantMessage("fine"));
        var result = answerOf(handler.handle(input("{\"connection\":\"c\",\"model\":\"m-2\",\"messages\":["
                + "{\"role\":\"user\",\"content\":\"hi\"},{\"role\":\"other\"}]}"), null));
        assertThat(result.get("text")).isEqualTo("fine");
        assertThat(prompts.getLast().getInstructions()).hasSize(1);
        assertThat(prompts.getLast().getOptions().getModel()).isEqualTo("m-2");
    }

    // ------------------------------------------------------------------ real provider clients, mocked HTTP

    private String serve(String json) throws IOException {
        reply = json;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private AgentTurnHandler realHandler(String provider, String baseUrl) {
        var connection = new LlmWorkerProperties.Connection();
        connection.setProvider(provider);
        connection.setApiKey("test-key");
        connection.setModel("model-x");
        connection.setBaseUrl(baseUrl);
        properties.getConnections().put("c", connection);
        return new AgentTurnHandler(properties, new SpringAiChatModelFactory(ObservationRegistry.NOOP), LlmCallMetrics.NONE);
    }

    @Test
    void anthropicSeesToolUseAndToolResultBlocksAndItsToolUseComesBackAsACall() throws Exception {
        var base = serve("""
                {"id": "msg_1", "type": "message", "role": "assistant", "model": "model-x",
                 "content": [{"type": "text", "text": "Checking the refund."},
                             {"type": "tool_use", "id": "toolu_9", "name": "lookupOrder", "input": {"orderId": "O-9"}}],
                 "stop_reason": "tool_use", "stop_sequence": null,
                 "usage": {"input_tokens": 41, "output_tokens": 17}}
                """);
        var result = answerOf(realHandler("anthropic", base).handle(input(REQUEST), null));

        assertThat(paths.getFirst()).endsWith("/v1/messages");
        JsonNode sent = JSON.readTree(bodies.getFirst());
        assertThat(sent.path("tools").get(0).path("name").asText()).isEqualTo("lookupOrder");
        assertThat(sent.path("tools").get(0).path("input_schema").path("required").get(0).asText()).isEqualTo("orderId");
        assertThat(sent.toString()).contains("\"type\":\"tool_use\"").contains("\"id\":\"toolu_1\"")
                .contains("\"type\":\"tool_result\"").contains("\"tool_use_id\":\"toolu_1\"").contains("SHIPPED");
        assertThat(sent.path("system").toString()).contains("Be brief.");

        assertThat(result.get("text")).isEqualTo("Checking the refund.");
        assertThat(result.get("toolCalls")).asList().hasSize(1);
        @SuppressWarnings("unchecked")
        var call = (Map<String, Object>) ((List<?>) result.get("toolCalls")).getFirst();
        assertThat(call).containsEntry("id", "toolu_9").containsEntry("name", "lookupOrder");
        assertThat(JSON.readTree(String.valueOf(call.get("arguments"))).path("orderId").asText()).isEqualTo("O-9");
        assertThat(result.get("usage")).isEqualTo(Map.of("inputTokens", 41, "outputTokens", 17));
    }

    @Test
    void openAiSeesFunctionToolsAndToolMessagesAndItsToolCallsComeBack() throws Exception {
        var base = serve("""
                {"id": "chatcmpl-1", "object": "chat.completion", "created": 1760000000, "model": "model-x",
                 "choices": [{"index": 0, "finish_reason": "tool_calls", "logprobs": null,
                   "message": {"role": "assistant", "content": null, "refusal": null,
                     "tool_calls": [{"id": "call_7", "type": "function",
                       "function": {"name": "lookupOrder", "arguments": "{\\"orderId\\":\\"O-7\\"}"}}]}}],
                 "usage": {"prompt_tokens": 33, "completion_tokens": 9, "total_tokens": 42}}
                """);
        var result = answerOf(realHandler("openai", base).handle(input(REQUEST), null));

        assertThat(paths.getFirst()).endsWith("/chat/completions");
        JsonNode sent = JSON.readTree(bodies.getFirst());
        assertThat(sent.path("tools").get(0).path("type").asText()).isEqualTo("function");
        assertThat(sent.path("tools").get(0).path("function").path("name").asText()).isEqualTo("lookupOrder");
        var messages = sent.path("messages");
        assertThat(messages.get(2).path("tool_calls").get(0).path("id").asText()).isEqualTo("toolu_1");
        assertThat(messages.get(3).path("role").asText()).isEqualTo("tool");
        assertThat(messages.get(3).path("tool_call_id").asText()).isEqualTo("toolu_1");

        assertThat(result.get("toolCalls")).isEqualTo(List.of(Map.of("id", "call_7", "name", "lookupOrder",
                "arguments", "{\"orderId\":\"O-7\"}")));
        assertThat(result.get("usage")).isEqualTo(Map.of("inputTokens", 33, "outputTokens", 9));
    }
}
