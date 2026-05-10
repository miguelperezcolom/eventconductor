package io.mateu.workflow.iaagentservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/agent")
public class IaAgentController {

    private static final Logger log = LoggerFactory.getLogger(IaAgentController.class);

    private final ChatClient chatClient;
    private final PerRequestMcpClientFactory mcpFactory;
    private final ConversationStore conversationStore;
    private final String baseSystemPrompt;

    public IaAgentController(ChatClient.Builder builder,
                              PerRequestMcpClientFactory mcpFactory,
                              ConversationStore conversationStore) throws IOException {
        this.mcpFactory = mcpFactory;
        this.conversationStore = conversationStore;
        this.baseSystemPrompt = new ClassPathResource("system-prompt-base.txt")
                .getContentAsString(StandardCharsets.UTF_8);
        this.chatClient = builder.build();
    }

    private String buildSystemPrompt(String serverContext) {
        if (serverContext == null || serverContext.isBlank()) {
            return baseSystemPrompt;
        }
        return baseSystemPrompt + "\n\nContexto de las herramientas disponibles:\n\n" + serverContext;
    }

    // ── Internal result record ──────────────────────────────────────────────
    private record LlmResult(String content, int inputTokens, int outputTokens, int totalTokens) {}

    // ── SSE helpers ─────────────────────────────────────────────────────────
    private ServerSentEvent<String> tokenEvent(int input, int output, int total) {
        return ServerSentEvent.<String>builder()
                .data("{\"inputTokens\":" + input
                        + ",\"outputTokens\":" + output
                        + ",\"totalTokens\":" + total + "}")
                .build();
    }

    private ServerSentEvent<String> contentEvent(String text) {
        return ServerSentEvent.<String>builder().data(text).build();
    }

    // ── /chat  (non-streaming, kept for backwards-compat) ───────────────────
    @GetMapping(value = "/chat", produces = "text/plain;charset=UTF-8")
    public String chat(@RequestParam String message,
                       @RequestParam String sessionId,
                       @RequestHeader(value = "Authorization", required = false) String authorization) {
        log.info("Chat request session={}: '{}'", sessionId, message);
        try (var tools = mcpFactory.createTools(authorization)) {
            String systemPrompt = buildSystemPrompt(tools.getServerSystemContext());
            var history = conversationStore.getHistory(sessionId);

            var response = chatClient.prompt()
                    .system(systemPrompt)
                    .messages(history)
                    .user(message)
                    .toolCallbacks(tools.getCallbacks())
                    .call();

            var content = response.content();
            log.info("Chat response session={} (null={}): '{}'", sessionId, content == null, content);
            if (content == null || content.isBlank()) {
                log.warn("Blank content — raw chatResponse: {}", response.chatResponse());
            }
            String result = (content != null && !content.isBlank()) ? content : "(sin respuesta)";
            conversationStore.addExchange(sessionId, message, result);
            return result;
        } catch (Exception e) {
            log.error("Error en chat session={} — {}: {}", sessionId, e.getClass().getName(), e.getMessage());
            log.error("Full stack:", e);
            Throwable cause = e.getCause();
            while (cause != null) {
                log.error("Caused by — {}: {}", cause.getClass().getName(), cause.getMessage());
                cause = cause.getCause();
            }
            return "Error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    // ── /stream  (SSE, with periodic token-usage events) ────────────────────
    /**
     * SSE endpoint. Emits token-usage events every 2 seconds while the LLM is
     * processing, then a final token-usage event with the real counts followed
     * by the content event.
     *
     * Token payload: {"inputTokens": N, "outputTokens": M, "totalTokens": T}
     * Content payload: plain text (no wrapping object).
     *
     * Internally uses .call() (not .stream()) because Spring AI 1.0.0 does not
     * execute the tool-call loop when using .stream() — tool_use blocks are
     * silently dropped and no MCP tool is ever invoked.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestParam String message,
                                                @RequestParam String sessionId,
                                                @RequestHeader(value = "Authorization", required = false) String authorization) {
        log.info("Stream request session={}: '{}'", sessionId, message);
        var history = conversationStore.getHistory(sessionId);

        // Blocking LLM call on a dedicated thread; cache() so both takeUntilOther
        // and the final flatMapMany share the same single execution.
        Mono<LlmResult> resultMono = Mono.fromCallable(() -> {
                    try (var tools = mcpFactory.createTools(authorization)) {
                        String systemPrompt = buildSystemPrompt(tools.getServerSystemContext());
                        var chatResponse = chatClient.prompt()
                                .system(systemPrompt)
                                .messages(history)
                                .user(message)
                                .toolCallbacks(tools.getCallbacks())
                                .call()
                                .chatResponse();

                        String content = null;
                        int inputTokens = 0, outputTokens = 0, totalTokens = 0;

                        if (chatResponse != null) {
                            var result = chatResponse.getResult();
                            if (result != null && result.getOutput() != null) {
                                content = result.getOutput().getText();
                            }
                            var usage = chatResponse.getMetadata() != null
                                    ? chatResponse.getMetadata().getUsage() : null;
                            if (usage != null) {
                                inputTokens  = usage.getPromptTokens()     != null ? usage.getPromptTokens()     : 0;
                                outputTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
                                totalTokens  = usage.getTotalTokens()      != null ? usage.getTotalTokens()      : 0;
                            }
                        }

                        log.info("Stream completed session={}: {} chars, tokens={}/{}/{}",
                                sessionId, content != null ? content.length() : 0,
                                inputTokens, outputTokens, totalTokens);

                        String text = (content != null && !content.isBlank()) ? content : "(sin respuesta)";
                        conversationStore.addExchange(sessionId, message, text);
                        return new LlmResult(text, inputTokens, outputTokens, totalTokens);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .cache();

        // Emit a token-usage placeholder every 2 s while the LLM is still running.
        Flux<ServerSentEvent<String>> periodicTokens = Flux
                .interval(Duration.ZERO, Duration.ofSeconds(2))
                .map(i -> tokenEvent(0, 0, 0))
                .takeUntilOther(resultMono);

        // Once the LLM responds: emit the real token counts, then the content.
        Flux<ServerSentEvent<String>> finalEvents = resultMono
                .doOnError(e -> log.error("Stream error session={} — {}: {}",
                        sessionId, e.getClass().getName(), e.getMessage(), e))
                .onErrorReturn(new LlmResult("[Error al procesar la solicitud]", 0, 0, 0))
                .flatMapMany(r -> Flux.just(
                        tokenEvent(r.inputTokens(), r.outputTokens(), r.totalTokens()),
                        contentEvent(r.content())
                ));

        return Flux.concat(periodicTokens, finalEvents);
    }
}
