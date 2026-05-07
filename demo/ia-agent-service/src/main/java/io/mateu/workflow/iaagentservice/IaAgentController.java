package io.mateu.workflow.iaagentservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.CrossOrigin;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
                    Rol: Eres un asistente experto en orquestación de flujos de trabajo (Sagas).
                    Objetivo: ayudar a los operadores a monitorizar, diagnosticar y reparar procesos.
                    Herramientas disponibles: Tienes acceso a herramientas para consultar el estado del motor y enviar comandos de reintento.
                    Instrucciones: 
                    - Cuando veas un error, analiza los logs primero antes de sugerir un reintento.
                    - Se conciso y técnico en tus respuestas.
                    - Únicamente debes responder a peticiones que puedan ser contestadas llamando a herramientas.
                    - Enseña los resultados de las herramientas en la UI             
                    - Para enseñar los resultados de las herramientas, puedes utilizar los siguientes formatos:
                        - para enseñar una reserva, puedes utilizar el siguiente formato: xxxxx?q=<id de la reserva>                                
                        - para enseñar un proceso, puedes utilizar el siguiente formato: yyyy?q=<id del proceso>
                        - para enseñar el listado de reservas, puedes utilizar el siguiente formato: xxxxx?q=<ids de las reservas separadas por comas>                                
                        - para enseñar el listado de procesos, puedes utilizar el siguiente formato: yyyy?q=<ids de los procesos separados por comas>                                
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
     * Streaming via SSE. Internally uses .call() (not .stream()) because Spring AI 1.0.0
     * does not execute the tool-call loop when using .stream().content() — tool_use blocks
     * emitted by the model are silently dropped and no MCP tool is ever invoked.
     * .call() runs the full agentic loop; we wrap it in Mono.fromCallable + boundedElastic
     * so the blocking MCP calls don't run on a Netty/Reactor event-loop thread.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestParam String message) {
        log.info("Stream request: '{}'", message);

        return Mono.fromCallable(() -> {
                    var content = chatClient.prompt()
                            .user(message)
                            .call()
                            .content();
                    log.info("Stream (call) completed: {} chars", content != null ? content.length() : 0);
                    return content != null ? content : "(sin respuesta)";
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> log.error("Stream error — {}: {}", e.getClass().getName(), e.getMessage(), e))
                .onErrorResume(e -> Mono.just("[Error: " + e.getMessage() + "]"))
                .flatMapMany(content -> Flux.just(
                        ServerSentEvent.<String>builder().data(content).build()));
    }
}