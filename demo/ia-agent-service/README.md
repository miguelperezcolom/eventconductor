# ia-agent-service

Servicio de agente IA para el ecosistema EventConductor. Expone una API REST que
permite a los operadores interactuar en lenguaje natural con los motores de
orquestación, formularios y reservas, delegando el razonamiento y la ejecución de
acciones al LLM de Anthropic (Claude) mediante el protocolo MCP.

## Arquitectura

```
Navegador / UI
      │  POST JSON (ChatRequest) + Authorization header
      ▼
IaAgentController  (/ai/api/agent/chat  |  /ai/api/agent/stream)
      │
      ├─ ConversationStore        historial de sesión + tokens acumulados (Caffeine)
      ├─ MenuContextStore         menú de navegación de la UI por sesión (Caffeine)
      ├─ AnthropicCacheInterceptor  inyecta cache_control en el system prompt
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
| Anthropic Prompt Caching | Spring AI 1.0.0 no soporta `cache_control` de forma nativa. `AnthropicCacheInterceptor` reescribe el JSON de la petición en vuelo, convirtiendo el system prompt de `String` a array de bloques con `"cache_control":{"type":"ephemeral"}`. El coste de los tokens cacheados es ~10% del precio normal. |
| Caché de menú de UI por sesión | El frontend envía el menú de pantallas disponibles en cada petición (o cuando cambia). `MenuContextStore` lo almacena por `sessionId` para no perderlo entre peticiones y lo inyecta en el system prompt con los comandos `[NAVIGATE:{...}]` exactos para cada pantalla. |
| Tokens acumulados por sesión | El LLM es stateless — cada petición cuenta sus propios tokens. `ConversationStore` acumula los contadores por sesión y el cliente recibe el total de la sesión, no sólo el de la última petición. |

## Endpoints

Ambos endpoints aceptan `POST` con cuerpo JSON (`ChatRequest`):

```json
{
  "message": "¿Cuántos procesos hay en estado ERROR?",
  "sessionId": "browser-generated-uuid",
  "menuContext": [
    {
      "path": ["Reservas", "Lista de reservas"],
      "navigation": {
        "route": "/booking/bookings",
        "consumedRoute": "",
        "actionId": "",
        "baseUrl": "/_booking",
        "serverSideType": "io.mateu.workflow.booking.infra.in.ui.BookingHome",
        "uriPrefix": ""
      }
    }
  ]
}
```

`menuContext` es opcional y sólo necesita enviarse cuando el menú cambia (el servidor
lo almacena en caché por `sessionId`).

### `POST /ai/api/agent/chat`

Respuesta síncrona en texto plano.

| Campo | Tipo | Descripción |
|---|---|---|
| `message` | body (JSON) | Pregunta o instrucción del operador |
| `sessionId` | body (JSON) | Identificador de sesión del navegador |
| `menuContext` | body (JSON, opcional) | Pantallas disponibles en la UI |
| `Authorization` | header (opcional) | Token que se reenvía a todos los MCP servers |

**Respuesta:** `text/plain;charset=UTF-8`

### `POST /ai/api/agent/stream`

Respuesta en streaming via Server-Sent Events. Internamente usa `.call()` para
ejecutar el bucle completo de tool-use y emite los resultados como eventos SSE.

| Campo | Tipo | Descripción |
|---|---|---|
| `message` | body (JSON) | Pregunta o instrucción del operador |
| `sessionId` | body (JSON) | Identificador de sesión del navegador |
| `menuContext` | body (JSON, opcional) | Pantallas disponibles en la UI |
| `Authorization` | header (opcional) | Token que se reenvía a todos los MCP servers |

**Respuesta:** `text/event-stream`

Eventos SSE emitidos:

| Evento (`data`) | Descripción |
|---|---|
| `{"inputTokens":N,"outputTokens":M,"totalTokens":T}` | Contadores de tokens acumulados de la sesión. Se emite como placeholder cada 2 s mientras el LLM trabaja y con los valores reales al terminar. |
| `{"event":"navigation-requested","detail":{...}}` | Comando de navegación extraído de la respuesta del LLM (si incluyó un bloque `[NAVIGATE:{...}]`). |
| `<texto de la respuesta>` | Texto de la respuesta del agente, con los bloques `[NAVIGATE:{...}]` eliminados. |
| `{"event":"agent-error","detail":{"message":"..."}}` | Error estructurado si el LLM o los MCP servers fallan. |

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

El system prompt se construye en cada petición en tres capas:

1. **Texto base** — `src/main/resources/system-prompt-base.txt`: rol, instrucciones
   generales y formatos de visualización en la UI. Editable sin recompilar.

2. **Contexto de servidores** — cada servidor MCP puede exponer un MCP Prompt llamado
   `system-context` que describe su dominio (estados de proceso, campos de formulario,
   estados de reserva, etc.). El agente los lee al conectarse y los añade al texto base.

3. **Menú de UI** — `MenuContextStore` añade una sección con las pantallas disponibles
   en la interfaz y el comando `[NAVIGATE:{...}]` exacto para abrir cada una. Esto
   permite al LLM navegar a una pantalla concreta en respuesta a una pregunta del usuario.

El system prompt completo se cachea en Anthropic (server-side prompt caching) mediante
`AnthropicCacheInterceptor`. Los tokens cacheados cuestan ~10% del precio normal, lo
que reduce significativamente el coste cuando el system prompt es estable entre peticiones.

### Navegación desde el agente

Si el LLM incluye un bloque `[NAVIGATE:{...}]` en su respuesta, el controlador lo
extrae, lo emite como evento SSE `navigation-requested` y lo elimina del texto visible.
El cliente web puede suscribirse a ese evento para navegar automáticamente a la pantalla
indicada sin que el usuario tenga que hacer clic.

## Historial de conversación y tokens

`ConversationStore` mantiene en memoria los últimos **5 intercambios**
(10 mensajes: 5 de usuario + 5 de asistente) por sesión, y acumula los contadores
de tokens a lo largo de toda la sesión:

- **Capacidad**: 1.000 sesiones simultáneas
- **TTL**: 30 minutos de inactividad
- **Thread-safety**: actualizaciones atómicas via `Cache.asMap().compute()` / `merge()`
- **Tokens acumulados**: `accumulateTokens(sessionId, input, output, total)` suma los
  tokens de cada petición al total de la sesión; `getTotalTokens(sessionId)` devuelve
  el acumulado. El cliente recibe el total de la sesión, no sólo el de la última petición.

El `sessionId` debe ser generado y persistido por el cliente (navegador) para que el
contexto de conversación se mantenga entre peticiones.

## Estructura del módulo

```
ia-agent-service/
├── src/main/java/io/mateu/workflow/iaagentservice/
│   ├── IaAgentServiceApplication.java     arranque Spring Boot
│   ├── IaAgentController.java             endpoints REST /chat y /stream
│   ├── ChatRequest.java                   DTO de petición (message, sessionId, menuContext)
│   ├── PerRequestMcpClientFactory.java    crea conexiones MCP frescas por prompt
│   ├── ConversationStore.java             historial de sesión + tokens acumulados (Caffeine)
│   ├── MenuContextStore.java              caché del menú de UI por sesión (Caffeine)
│   ├── AnthropicCacheInterceptor.java     inyecta cache_control en el system prompt
│   ├── AnthropicCacheConfig.java          registra el interceptor vía RestClientCustomizer
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
