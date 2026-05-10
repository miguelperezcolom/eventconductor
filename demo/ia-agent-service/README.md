# ia-agent-service

Servicio de agente IA para el ecosistema EventConductor. Expone una API REST que
permite a los operadores interactuar en lenguaje natural con los motores de
orquestación, formularios y reservas, delegando el razonamiento y la ejecución de
acciones al LLM de Anthropic (Claude) mediante el protocolo MCP.

## Arquitectura

```
Navegador / UI
      │  Authorization header + sessionId
      ▼
IaAgentController  (/api/agent/chat  |  /api/agent/stream)
      │
      ├─ ConversationStore        historial de sesión (Caffeine, últimos 5 intercambios)
      │
      └─ PerRequestMcpClientFactory
              │  nueva conexión SSE por cada prompt
              ├─► MCP server: event-conductor  (puerto 8105)
              ├─► MCP server: forms-engine     (puerto 8106)
              └─► MCP server: booking-service  (puerto 8108)
                      │
                      └─ Authorization header propagado a cada servidor
```

### Decisiones de diseño

| Decisión | Motivo |
|---|---|
| Conexión MCP nueva por prompt | Una conexión SSE persistente queda rota si el servidor MCP se reinicia. Abriendo una conexión fresca por petición el agente es resiliente a caídas de los MCP. |
| System prompt dinámico | Cada servidor MCP expone un MCP Prompt `system-context` con la descripción de su dominio. El agente lo lee en cada petición y lo combina con el texto base local, sin necesidad de cambiar código cuando cambian los servidores. |
| Historial de conversación | Los últimos 5 intercambios se almacenan en caché Caffeine por `sessionId` y se envían al LLM en cada petición, manteniendo el contexto de la conversación. |
| `.call()` en lugar de `.stream()` | Spring AI 1.0.0 no ejecuta el bucle de tool-use con `.stream()` — los bloques `tool_use` se descartan silenciosamente. `.call()` sí ejecuta el bucle completo. |
| Executor dedicado para tool calls | `AnthropicChatModel` invoca los callbacks de herramientas en el hilo del event loop de Netty. Reactor prohíbe `.block()` ahí. Los tool calls se delegan a un thread pool y se espera con `Future.get()` (Java plano, sin Reactor). |

## Endpoints

### `GET /api/agent/chat`

Respuesta síncrona en texto plano.

| Parámetro | Tipo | Descripción |
|---|---|---|
| `message` | query param | Pregunta o instrucción del operador |
| `sessionId` | query param | Identificador de sesión del navegador |
| `Authorization` | header (opcional) | Token que se reenvía a todos los MCP servers |

**Respuesta:** `text/plain;charset=UTF-8`

### `GET /api/agent/stream`

Respuesta en streaming via Server-Sent Events. Internamente usa `.call()` para
ejecutar el bucle completo de tool-use y emite el resultado como un único evento SSE.

| Parámetro | Tipo | Descripción |
|---|---|---|
| `message` | query param | Pregunta o instrucción del operador |
| `sessionId` | query param | Identificador de sesión del navegador |
| `Authorization` | header (opcional) | Token que se reenvía a todos los MCP servers |

**Respuesta:** `text/event-stream`

## Configuración

### Variables de entorno

| Variable | Descripción |
|---|---|
| `ANTHROPIC_API_KEY` | API key de Anthropic (obligatoria) |

### `application.yaml` — parámetros relevantes

```yaml
spring:
  ai:
    anthropic:
      chat:
        options:
          model: claude-sonnet-4-5   # modelo a utilizar
          temperature: 0.1           # respuestas más deterministas
          max-tokens: 4096

    mcp:
      client:
        enabled: false               # desactiva clientes persistentes
        request-timeout: 60s
        sse:
          connections:
            event-conductor:
              url: http://localhost:8105
            forms-engine:
              url: http://localhost:8106
            booking-service:
              url: http://localhost:8108

server:
  port: 8095
```

Para añadir un nuevo servidor MCP basta con agregar una entrada en
`spring.ai.mcp.client.sse.connections`. Si el nuevo servidor implementa el MCP Prompt
`system-context`, su descripción de dominio se incorporará automáticamente al system
prompt del agente.

## System prompt

El system prompt se construye en cada petición en dos capas:

1. **Texto base** — `src/main/resources/system-prompt-base.txt`: rol, instrucciones
   generales y formatos de visualización en la UI. Editable sin recompilar.

2. **Contexto de servidores** — cada servidor MCP puede exponer un MCP Prompt llamado
   `system-context` que describe su dominio (estados de proceso, campos de formulario,
   estados de reserva, etc.). El agente los lee al conectarse y los añade al texto base.

## Historial de conversación

`ConversationStore` mantiene en memoria los últimos **5 intercambios**
(10 mensajes: 5 de usuario + 5 de asistente) por sesión:

- **Capacidad**: 1.000 sesiones simultáneas
- **TTL**: 30 minutos de inactividad
- **Thread-safety**: actualizaciones atómicas via `Cache.asMap().compute()`

El `sessionId` debe ser generado y persistido por el cliente (navegador) para que el
contexto de conversación se mantenga entre peticiones.

## Estructura del módulo

```
ia-agent-service/
├── src/main/java/io/mateu/workflow/iaagentservice/
│   ├── IaAgentServiceApplication.java     arranque Spring Boot
│   ├── IaAgentController.java             endpoints REST /chat y /stream
│   ├── PerRequestMcpClientFactory.java    crea conexiones MCP frescas por prompt
│   ├── ConversationStore.java             caché Caffeine de historial por sesión
│   └── McpSseConnectionProperties.java    lee las URLs de los MCP servers del YAML
└── src/main/resources/
    ├── application.yaml
    └── system-prompt-base.txt             texto base del system prompt
```

## Arranque

```bash
export ANTHROPIC_API_KEY=sk-ant-...
mvn spring-boot:run
```

El servicio estará disponible en `http://localhost:8095`.

## Dependencias externas

| Servicio | Puerto por defecto | MCP server name |
|---|---|---|
| event-conductor (orchestrator) | 8105 | `event-conductor` |
| forms-engine | 8106 | `forms-engine` |
| booking-service | 8108 | `booking-service` |

El agente tolera que uno o más servidores MCP estén caídos al iniciar una petición:
los servidores no disponibles se omiten y los que sí responden se utilizan con
normalidad.
