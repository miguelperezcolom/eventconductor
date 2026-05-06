package io.mateu.workflow.iaagentservice;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/agent")
public class IaAgentController {

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
    @GetMapping("/chat")
    public String chat(@RequestParam String message) {
        return chatClient.prompt()
                .user(message)
                .call()
                .content();
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