---
title: ia-agent-service
description: AI agent powered by Claude that lets operators interact with EventConductor in natural language.
---

`ia-agent-service` is an AI agent powered by Claude (Anthropic) that lets operators interact with the orchestration engine, forms engine, and custom domain services in natural language via MCP tools.

## Architecture

```
Browser / UI
      │  POST JSON (ChatRequest) + Authorization header
      ▼
IaAgentController  (/ai/api/agent/chat  |  /ai/api/agent/stream)
      │
      ├─ ConversationStore        conversation history + accumulated tokens (Caffeine)
      ├─ MenuContextStore         UI navigation menu per session (Caffeine)
      ├─ AnthropicCacheInterceptor  injects cache_control into the system prompt
      │
      └─ PerRequestMcpClientFactory
              │  fresh SSE connection per prompt
              ├─► MCP server: event-conductor  (port 8105)
              ├─► MCP server: forms-engine     (port 8106)
              └─► MCP server: booking-service  (port 8108)
```

## Endpoints

Both endpoints accept `POST` with a JSON body:

```json
{
  "message": "How many processes are in ERROR state?",
  "sessionId": "browser-generated-uuid",
  "menuContext": [
    {
      "path": ["Bookings", "Booking List"],
      "navigation": {
        "route": "/booking/bookings",
        "baseUrl": "/_booking"
      }
    }
  ]
}
```

`menuContext` is optional and only needs to be sent when the menu changes — the server caches it per `sessionId`.

### `POST /ai/api/agent/chat`

Synchronous response as plain text.

### `POST /ai/api/agent/stream`

Streaming response via Server-Sent Events. Internally uses `.call()` to execute the full tool-use loop and emits results as SSE events.

**SSE events emitted:**

| Event (`data`) | Description |
|---|---|
| `{"inputTokens":N,"outputTokens":M,"totalTokens":T}` | Accumulated token counters for the session. Emitted as a placeholder every 2s while the LLM works, and with real values when done. |
| `{"event":"navigation-requested","detail":{...}}` | Navigation command extracted from the LLM response (if it included a `[NAVIGATE:{...}]` block). |
| `<response text>` | Agent response text, with `[NAVIGATE:{...}]` blocks removed. |
| `{"event":"agent-error","detail":{"message":"..."}}` | Structured error if the LLM or MCP servers fail. |

## Configuration

### Environment variables

| Variable | Description |
|---|---|
| `ANTHROPIC_API_KEY` | Anthropic API key (required) |

### `application.yaml`

```yaml
spring:
  ai:
    anthropic:
      chat:
        options:
          model: claude-sonnet-4-5
          temperature: 0.1
          max-tokens: 4096

    mcp:
      client:
        enabled: false
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

To add a new MCP server, add an entry under `spring.ai.mcp.client.sse.connections`.

## System prompt

Built in three layers on each request:

1. **Base text** — `src/main/resources/system-prompt-base.txt`: role, general instructions, and UI display formats. Editable without recompiling.

2. **Server context** — each MCP server can expose a `system-context` MCP Prompt describing its domain. The agent reads these on each connection and appends them to the base text.

3. **UI menu** — `MenuContextStore` adds a section with available UI screens and the exact `[NAVIGATE:{...}]` command to open each one. This lets the LLM navigate to a specific screen in response to a user question.

The complete system prompt is cached on Anthropic (server-side prompt caching) via `AnthropicCacheInterceptor`. Cached tokens cost ~10% of normal price.

## Conversation history and tokens

`ConversationStore` keeps in memory the last **5 exchanges** (10 messages: 5 user + 5 assistant) per session, and accumulates token counters throughout the session:

- **Capacity**: 1,000 simultaneous sessions
- **TTL**: 30 minutes of inactivity
- **Thread-safe**: atomic updates via `Cache.asMap().compute()` / `merge()`
- **Accumulated tokens**: `accumulateTokens()` sums tokens per request to the session total; the client receives the session total, not just the last request.

The `sessionId` must be generated and persisted by the client (browser) so conversation context is maintained across requests.

## UI navigation

If the LLM includes a `[NAVIGATE:{...}]` block in its response, the controller extracts it, emits it as a `navigation-requested` SSE event, and removes it from the visible text. The web client can subscribe to that event to navigate automatically to the indicated screen without the user having to click.

## Starting the service

```bash
export ANTHROPIC_API_KEY=sk-ant-...
cd demo/ia-agent-service
mvn spring-boot:run
```

The service will be available at `http://localhost:8095`.
