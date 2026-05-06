package io.mateu.workflow.iaagentservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.CrossOrigin;
import reactor.core.publisher.Flux;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/agent")
public class IaAgentController {

    private static final Logger log = LoggerFactory.getLogger(IaAgentController.class);

    private final ChatClient chatClient;

    public IaAgentController(ChatClient.Builder builder, ToolCallbackProvider toolCallbackProvider) {
        this.chatClient = builder
                .defaultToolCallbacks(toolCallbackProvider.getToolCallbacks())
                .defaultSystem("""
                    Eres un asistente experto en orquestación de flujos de trabajo (Sagas).
                    Tu objetivo es ayudar a los operadores a monitorizar, diagnosticar y reparar procesos.
                    Tienes acceso a herramientas para consultar el estado del motor y enviar comandos de reintento.
                    Cuando veas un error, analiza los logs primero antes de sugerir un reintento.
                    Se conciso y técnico en tus respuestas.
                    """)
                .build();
    }

    /**
     * Endpoint para hablar con el agente.
     * El agente usará automáticamente las herramientas del MCP si lo considera necesario.
     */
    @GetMapping(value = "/chat", produces = "text/plain;charset=UTF-8")
    public String chat(@RequestParam String message) {
        log.info("Chat request: '{}'", message);
        try {
            var response = chatClient.prompt()
                    .user(message)
                    .call();
            var content = response.content();
            log.info("Chat response (null={}): '{}'", content == null, content);
            if (content == null || content.isBlank()) {
                log.warn("Blank content — raw chatResponse: {}", response.chatResponse());
            }
            return (content != null && !content.isBlank()) ? content : "(sin respuesta)";
        } catch (Exception e) {
            log.error("Error en chat — {}: {}", e.getClass().getName(), e.getMessage());
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
     * Versión reactiva (Streaming) por si quieres que la respuesta aparezca poco a poco.
     */
    @GetMapping(value = "/stream", produces = "text/event-stream")
    public Flux<String> stream(@RequestParam String message) {
        return chatClient.prompt()
                .user(message)
                .stream()
                .content();
    }
}