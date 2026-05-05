package io.mateu.workflow.ia.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/agent")
public class IaAgentController {

    private final ChatClient chatClient;

    public IaAgentController(ChatClient.Builder builder) {
        // Configuramos el cliente de chat con memoria para que recuerde el hilo de la conversación
        this.chatClient = builder
                .defaultAdvisors(new MessageChatMemoryAdvisor(new InMemoryChatMemory()))
                .defaultSystem("""
                    Eres un asistente experto en orquestación de flujos de trabajo (Sagas).
                    Tu objetivo es ayudar a los operadores a monitorizar, diagnosticar y reparar procesos.
                    Tienes acceso a herramientas para consultar la base de datos y enviar comandos de reintento.
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