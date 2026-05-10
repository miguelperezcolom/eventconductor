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
        // No defaultToolCallbacks nor defaultSystem here: both are injected per-request so
        // each prompt gets fresh MCP connections and an up-to-date system prompt.
        this.chatClient = builder.build();
    }

    private String buildSystemPrompt(String serverContext) {
        if (serverContext == null || serverContext.isBlank()) {
            return baseSystemPrompt;
        }
        return baseSystemPrompt + "\n\nContexto de las herramientas disponibles:\n\n" + serverContext;
    }

    /**
     * Endpoint para hablar con el agente.
     * sessionId identifica la sesión del navegador; se usa para mantener el historial
     * de los últimos 5 intercambios que se envían al LLM como contexto.
     */
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

    /**
     * Streaming via SSE. Internally uses .call() (not .stream()) because Spring AI 1.0.0
     * does not execute the tool-call loop when using .stream().content() — tool_use blocks
     * emitted by the model are silently dropped and no MCP tool is ever invoked.
     *
     * sessionId identifica la sesión del navegador para mantener el historial de conversación.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestParam String message,
                                                @RequestParam String sessionId,
                                                @RequestHeader(value = "Authorization", required = false) String authorization) {
        log.info("Stream request session={}: '{}'", sessionId, message);
        var history = conversationStore.getHistory(sessionId);

        return Mono.fromCallable(() -> {
                    try (var tools = mcpFactory.createTools(authorization)) {
                        String systemPrompt = buildSystemPrompt(tools.getServerSystemContext());
                        var content = chatClient.prompt()
                                .system(systemPrompt)
                                .messages(history)
                                .user(message)
                                .toolCallbacks(tools.getCallbacks())
                                .call()
                                .content();
                        log.info("Stream (call) completed session={}: {} chars", sessionId,
                                content != null ? content.length() : 0);
                        String result = content != null ? content : "(sin respuesta)";
                        conversationStore.addExchange(sessionId, message, result);
                        return result;
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> log.error("Stream error session={} — {}: {}",
                        sessionId, e.getClass().getName(), e.getMessage(), e))
                .onErrorResume(e -> Mono.just("[Error: " + e.getMessage() + "]"))
                .flatMapMany(content -> Flux.just(
                        ServerSentEvent.<String>builder().data(content).build()));
    }
}
