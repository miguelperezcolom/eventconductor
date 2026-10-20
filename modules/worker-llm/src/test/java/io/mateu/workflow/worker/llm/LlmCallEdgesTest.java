package io.mateu.workflow.worker.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.mateu.workflow.worker.api.TaskFailure;
import io.mateu.workflow.worker.api.TaskRegistration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The llm-call task's less travelled paths: native structured output, answers missing pieces, how each
 * kind of provider failure is classified, the provider defaults a connection sets, and the wiring.
 */
class LlmCallEdgesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCHEMA = "{\"type\":\"object\",\"required\":[\"n\"],"
            + "\"properties\":{\"n\":{\"type\":\"integer\"}}}";

    /**
     * Spring AI's retry exceptions, by name: the handler recognises them by their simple name so that it
     * needs no Spring AI retry module on the classpath.
     */
    static class NonTransientAiException extends RuntimeException {
        NonTransientAiException(String message) {
            super(message);
        }
    }

    static class TransientAiException extends RuntimeException {
        TransientAiException(String message) {
            super(message);
        }
    }

    final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    volatile Function<Prompt, ChatResponse> answer;
    volatile ChatOptions defaults;
    LlmWorkerProperties properties;
    LlmWorkerProperties.Connection connection;
    SimpleMeterRegistry meters;
    LlmCallHandler handler;

    @BeforeEach
    void setUp() {
        properties = new LlmWorkerProperties();
        connection = new LlmWorkerProperties.Connection();
        connection.setApiKey("k");
        connection.setModel("m-1");
        properties.getConnections().put("c", connection);
        meters = new SimpleMeterRegistry();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                prompts.add(prompt);
                return answer.apply(prompt);
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return defaults;
            }
        };
        handler = new LlmCallHandler(properties, (name, c) -> model, new LlmCallMetrics(meters));
    }

    private static ChatResponse response(String text, String finishReason, DefaultUsage usage) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason(finishReason).build())),
                ChatResponseMetadata.builder().model("m-1").usage(usage).build());
    }

    private Map<String, Object> call(String request) throws Exception {
        return handler.handle(new LlmCallHandler.Input(JSON.readTree(request)), null);
    }

    private TaskFailure failure(Throwable thrown) {
        answer = prompt -> {
            throw thrown instanceof RuntimeException r ? r : new RuntimeException(thrown);
        };
        try {
            call("{\"connection\": \"c\", \"prompt\": \"x\"}");
        } catch (TaskFailure failure) {
            return failure;
        } catch (Exception other) {
            throw new AssertionError(other);
        }
        throw new AssertionError("no failure");
    }

    // ── structured output ─────────────────────────────────────────────────────────────────────

    @Test
    void withNativeStructuredOutputTheSchemaGoesToTheProviderToo() throws Exception {
        connection.setStructuredOutput("native");
        defaults = StructuredOutputChatOptions.builder().build();
        answer = prompt -> response("{\"n\": 3}", "end_turn", new DefaultUsage(1, 2));

        var result = call("{\"connection\": \"c\", \"prompt\": \"x\", \"responseSchema\": " + SCHEMA
                + ", \"outputVariable\": \"out\"}");

        assertThat(result).containsEntry("out", "{\"n\":3}");
        var options = prompts.getFirst().getOptions();
        assertThat(options).isInstanceOf(StructuredOutputChatOptions.class);
        assertThat(JSON.readTree(((StructuredOutputChatOptions) options).getOutputSchema()))
                .isEqualTo(JSON.readTree(SCHEMA));
    }

    @Test
    void inPromptModeTheSchemaIsOnlyInTheSystemPrompt() throws Exception {
        defaults = StructuredOutputChatOptions.builder().build();
        answer = prompt -> response("{\"n\": 3}", "end_turn", null);

        call("{\"connection\": \"c\", \"prompt\": \"x\", \"responseSchema\": " + SCHEMA + "}");

        assertThat(((StructuredOutputChatOptions) prompts.getFirst().getOptions()).getOutputSchema()).isNull();
        assertThat(prompts.getFirst().getSystemMessage().getText()).startsWith("Answer with exactly one JSON document");
    }

    @Test
    void manyViolationsAreReportedFiveAtATime() {
        var schema = "{\"type\":\"object\",\"properties\":{"
                + "\"a\":{\"type\":\"integer\"},\"b\":{\"type\":\"integer\"},\"c\":{\"type\":\"integer\"},"
                + "\"d\":{\"type\":\"integer\"},\"e\":{\"type\":\"integer\"},\"f\":{\"type\":\"integer\"}}}";
        answer = prompt -> response("{\"a\":\"x\",\"b\":\"x\",\"c\":\"x\",\"d\":\"x\",\"e\":\"x\",\"f\":\"x\"}",
                "end_turn", null);

        assertThatThrownBy(() -> call("{\"connection\": \"c\", \"prompt\": \"x\", \"responseSchema\": " + schema + "}"))
                .isInstanceOfSatisfying(TaskFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("LLM_INVALID_OUTPUT");
                    assertThat(failure.getMessage()).endsWith("; …");
                });
    }

    @Test
    void anEmptyAnswerToAStructuredRequestIsInvalidAndRetryable() {
        answer = prompt -> new ChatResponse(List.of());
        assertThatThrownBy(() -> call("{\"connection\": \"c\", \"prompt\": \"x\", \"responseSchema\": " + SCHEMA + "}"))
                .isInstanceOfSatisfying(TaskFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("LLM_INVALID_OUTPUT");
                    assertThat(failure.retryable()).isTrue();
                    assertThat(failure.getMessage()).contains("empty");
                });
    }

    @Test
    void jsonIsFoundInsideProseOrAnArray() {
        assertThat(LlmCallHandler.extractJson("The answer: {\"n\": 1} — hope it helps")).isEqualTo("{\"n\":1}");
        assertThat(LlmCallHandler.extractJson("list: [1, 2]")).isEqualTo("[1,2]");
        assertThat(LlmCallHandler.extractJson("  [3] ")).isEqualTo("[3]");
        assertThatThrownBy(() -> LlmCallHandler.extractJson(null)).hasMessageContaining("empty");
    }

    @Test
    void aSchemaTheValidatorCannotReadIsAConfigurationError() {
        assertThatThrownBy(() -> call("{\"connection\": \"c\", \"prompt\": \"x\", "
                + "\"responseSchema\": {\"$ref\": \"#/nowhere\"}}"))
                .isInstanceOfSatisfying(TaskFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("LLM_CONFIG");
                    assertThat(failure.retryable()).isFalse();
                });
    }

    // ── answers and outputs ───────────────────────────────────────────────────────────────────

    @Test
    void anAnswerWithoutUsageOrMetadataStillCompletesAndTotalsAreComputedWhenMissing() throws Exception {
        answer = prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("hi"))));
        var bare = call("{\"connection\": \"c\", \"prompt\": \"x\", \"outputVariable\": \"o\", \"usageVariable\": \"u\"}");
        assertThat(bare).containsEntry("o", "hi");
        @SuppressWarnings("unchecked")
        var usage = (Map<String, Object>) bare.get("u");
        assertThat(usage).containsEntry("model", "m-1");

        // Input and output known, total not reported: the total is their sum.
        answer = prompt -> response("hi", "stop", new DefaultUsage(10, 5, null, null) {
            @Override
            public Integer getTotalTokens() {
                return null;
            }
        });
        @SuppressWarnings("unchecked")
        var summed = (Map<String, Object>) call("{\"connection\": \"c\", \"prompt\": \"x\", \"usageVariable\": \"u\"}").get("u");
        assertThat(summed).containsEntry("totalTokens", 15);
    }

    @Test
    void anOutputExpressionThatCannotBeEvaluatedFailsTheStepFinally() {
        answer = prompt -> response("hi", "stop", null);
        assertThatThrownBy(() -> call("{\"connection\": \"c\", \"prompt\": \"x\", \"output\": {\"bad\": \"text.(\"}}"))
                .isInstanceOfSatisfying(TaskFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("LLM_OUTPUT");
                    assertThat(failure.retryable()).isFalse();
                    assertThat(failure.getMessage()).contains("'bad'");
                });
    }

    @Test
    void aRequestWithoutAPromptOrConnectionIsRefusedFinally() {
        assertThatThrownBy(() -> call("{\"connection\": \"c\", \"prompt\": \"  \"}"))
                .isInstanceOfSatisfying(TaskFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("LLM_REQUEST");
                    assertThat(failure.retryable()).isFalse();
                });
        assertThatThrownBy(() -> call("{\"prompt\": \"x\"}"))
                .isInstanceOfSatisfying(TaskFailure.class, failure -> assertThat(failure.code()).isEqualTo("LLM_CONFIG"));
        assertThatThrownBy(() -> handler.handle(new LlmCallHandler.Input(JSON.readTree("\"text\"")), null))
                .isInstanceOfSatisfying(TaskFailure.class, failure -> assertThat(failure.code()).isEqualTo("LLM_REQUEST"));
        assertThatThrownBy(() -> handler.handle(null, null))
                .isInstanceOfSatisfying(TaskFailure.class, failure -> assertThat(failure.code()).isEqualTo("LLM_REQUEST"));
    }

    // ── provider failures ─────────────────────────────────────────────────────────────────────

    @Test
    void timeoutsAndTransientErrorsAreRetriedWhileNonTransientOnesAreFinal() {
        assertThat(failure(new RuntimeException(new SocketTimeoutException("read timed out"))))
                .satisfies(f -> {
                    assertThat(f.code()).isEqualTo("LLM_IO");
                    assertThat(f.retryable()).isTrue();
                });
        assertThat(failure(new RuntimeException(new TimeoutException("deadline")))).satisfies(f -> {
            assertThat(f.code()).isEqualTo("LLM_IO");
            assertThat(f.retryable()).isTrue();
        });
        assertThat(failure(new NonTransientAiException("model not found"))).satisfies(f -> {
            assertThat(f.code()).isEqualTo("LLM_ERROR");
            assertThat(f.retryable()).isFalse();
        });
        assertThat(failure(new TransientAiException("overloaded"))).satisfies(f -> {
            assertThat(f.code()).isEqualTo("LLM_ERROR");
            assertThat(f.retryable()).isTrue();
        });
    }

    @Test
    void aFailureWithoutMessageIsNamedByItsTypeAndALongOneIsCut() {
        assertThat(failure(new IllegalStateException()).getMessage()).contains("IllegalStateException");
        assertThat(failure(new IllegalStateException("x".repeat(5_000))).getMessage()).contains("…")
                .hasSizeLessThan(1_200);
    }

    @Test
    void aTaskFailureFromTheModelIsPassedOnAsItIs() {
        var own = new TaskFailure("LLM_CONFIG", "no key", false);
        assertThat(LlmCallHandler.failureOf(own, "c")).isSameAs(own);
    }

    @Test
    void aStatusBelow400IsTreatedAsTransient() {
        assertThat(LlmCallHandler.failureOf(new LlmCallHandlerTest.ProviderException(302, "moved"), "c").retryable())
                .isTrue();
    }

    // ── providers ─────────────────────────────────────────────────────────────────────────────

    @Test
    void anAnthropicConnectionCarriesItsDefaultsIntoTheModel() {
        var anthropic = new LlmWorkerProperties.Connection();
        anthropic.setProvider("Anthropic");
        anthropic.setApiKey("k");
        anthropic.setBaseUrl("http://localhost:1");
        anthropic.setModel("claude-haiku");
        anthropic.setMaxTokens(256);
        anthropic.setTemperature(0.2);

        var options = (AnthropicChatOptions) new SpringAiChatModelFactory(null).create("a", anthropic).getDefaultOptions();

        assertThat(options.getModel()).isEqualTo("claude-haiku");
        assertThat(options.getMaxTokens()).isEqualTo(256);
        assertThat(options.getTemperature()).isEqualTo(0.2);
    }

    @Test
    void anOpenAiConnectionWithoutOptionalSettingsUsesTheProvidersDefaults() {
        var openai = new LlmWorkerProperties.Connection();
        openai.setProvider("openai");
        openai.setApiKey("k");
        openai.setMaxTokens(null);
        openai.setModel(" ");

        var options = (OpenAiChatOptions) new SpringAiChatModelFactory(null).create("o", openai).getDefaultOptions();

        assertThat(options.getTemperature()).isNull();
        assertThat(options.getMaxTokens()).isNull();
    }

    @Test
    void aConnectionWithoutAProviderIsAnthropic() {
        var unnamed = new LlmWorkerProperties.Connection();
        unnamed.setProvider(null);
        unnamed.setApiKey("k");
        unnamed.setModel("claude-x");
        assertThat(new SpringAiChatModelFactory(null).create("u", unnamed).getDefaultOptions())
                .isInstanceOf(AnthropicChatOptions.class);
        unnamed.setApiKey("  ");
        assertThatThrownBy(() -> new SpringAiChatModelFactory(null).create("u", unnamed))
                .hasMessageContaining("has no api-key");
    }

    // ── metrics and wiring ────────────────────────────────────────────────────────────────────

    @Test
    void withoutARegistryNothingIsRecordedAndUnknownTagsAreNamedSo() {
        var call = new LlmCallMetrics.Call(null, " ", "c", null);
        LlmCallMetrics.NONE.call(call, LlmCallMetrics.SUCCESS, 1);
        LlmCallMetrics.NONE.tokens(call, "input", 5);

        var metrics = new LlmCallMetrics(meters);
        metrics.tokens(call, "input", null);
        metrics.tokens(call, "input", 0);
        assertThat(meters.find(LlmCallMetrics.TOKENS).counter()).isNull();
        metrics.call(call, LlmCallMetrics.SUCCESS, 1);
        assertThat(meters.find(LlmCallMetrics.CALL).tags("workflow", "unknown", "step", "unknown", "model", "unknown")
                .timer()).isNotNull();
    }

    @Test
    void theTaskIsRegisteredUnlessTurnedOff() {
        var runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LlmWorkerAutoConfiguration.class));
        runner.withPropertyValues("workflow.llm.topic=my-llm").run(context -> {
            assertThat(context).hasSingleBean(LlmCallHandler.class);
            assertThat(context.getBean(ChatModelFactory.class)).isInstanceOf(SpringAiChatModelFactory.class);
            var registration = context.getBean(TaskRegistration.class);
            assertThat(registration.ref()).isEqualTo("llm-call@1");
            assertThat(registration.topic()).isEqualTo("my-llm");
        });
        runner.withPropertyValues("workflow.llm.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(LlmCallHandler.class));
    }
}
