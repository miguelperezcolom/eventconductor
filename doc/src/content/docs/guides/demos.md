---
title: Demo Applications
description: Overview of all runnable demo applications included in the EventConductor repository.
---

The `demo/` directory contains a suite of runnable applications that illustrate different
deployment patterns and integration scenarios. Each demo is a self-contained Spring Boot
project that can be started with `mvn spring-boot:run` from its own directory.

## Embedded demos

These demos run the workflow engine in-process — no Kafka broker required.

### `workflow-embedded`

**Mode:** `embedded` + `memory` | **Port:** 8080

The simplest possible demo. The engine runs fully in-process with no external dependencies.
Includes the web UI, the MCP endpoint, and a single "hello-world" workflow executed at startup.

```bash
cd demo/workflow-embedded && mvn spring-boot:run
```

Workflow definitions are loaded from `src/main/resources/workflows/`.

---

### `workflow-embedded-headless`

**Mode:** `embedded` + `memory` | **No HTTP server**

Same as `embedded` but with no web server — just a pure background JVM process. Useful for
showing how to embed the engine as a library inside an existing application without opening
any port.

```bash
cd demo/workflow-embedded-headless && mvn spring-boot:run
```

---

### `workflow-embedded-db-headless`

**Mode:** `embedded` + `jpa` (H2 in-memory) | **No HTTP server**

Embedded engine with JPA persistence via H2. State survives restarts within the same JVM
session. Shows how to add a real database without switching to Kafka.

```bash
cd demo/workflow-embedded-db-headless && mvn spring-boot:run
```

---

### `workflow-embedded-mvc`

**Mode:** `embedded` + `memory` | **Port:** 8080

Embedded engine exposed as a plain Spring MVC REST API — no MCP, no UI. Illustrates how to
wrap the engine with your own REST layer when you don't want to use the built-in UI or MCP
tools.

Endpoints:
- `POST /processes` — start a process instance
- `GET /processes/{id}` — get process state

```bash
cd demo/workflow-embedded-mvc && mvn spring-boot:run
```

---

### `workflow-embedded-git`

**Mode:** `embedded` + `jpa` (H2 in-memory) | **Port:** 8090

Embedded engine with JPA persistence and workflow definitions imported from GitHub at startup.
Demonstrates the Git import feature and the GitHub webhook endpoint for automatic re-import
after a push.

At startup it clones [`miguelperezcolom/sample-workflows`](https://github.com/miguelperezcolom/sample-workflows)
and imports all 23 workflow definitions it finds.

```bash
cd demo/workflow-embedded-git && mvn spring-boot:run
# → imports 23 workflow definitions from GitHub
# → UI available at http://localhost:8090
# → webhook at POST http://localhost:8090/workflow/webhooks/github
```

See [Importing from Git](/guides/workflow-definitions/#importing-from-git) for configuration details.

---

## Multi-service system

These demos form a realistic distributed system that showcases the full `kafka` + `jpa` mode.
They are designed to run together with a shared Kafka broker and PostgreSQL database.

### `api-gw`

JWT API Gateway. Issues and validates authentication tokens for the other services. Based on
Spring Cloud Gateway with a local RSA key pair — exposes `/.well-known/jwks.json` so
downstream services can verify tokens without calling back to the gateway.

### `booking-service`

A sample Kafka worker that subscribes to the `downstream` topic and processes booking-related
workflow steps (hotel and flight reservations). Illustrates how a domain microservice
participates in a workflow without any direct dependency on the engine.

### `content-service`

A content management microservice (secured with JWT) that acts as a Kafka worker for
content-publishing workflow steps. Shows how to combine standard REST security with
EventConductor worker integration.

### `control-plane-service`

Administrative control-plane service with Cloudflare API integration. Demonstrates running
the EventConductor management UI in a secured, operator-facing context separated from the
business services.

### `users-service`

User and group management microservice. Shows how an existing identity service can be
integrated as a workflow worker to handle user-onboarding steps (account creation, group
assignment, etc.).

### `shell`

Web shell / front-end host. Serves the single-page application that ties together the UI
components from the other services.

### `static` / `static-content-server`

Static asset servers. `static` serves compiled front-end assets; `static-content-server`
provides a configurable static resource endpoint used by the shell and other services.

### `sdks`

Client SDK examples showing how external applications can integrate with EventConductor —
starting processes, querying status, and handling events — using the provided client libraries.

---

## AI integration

### `ia-agent-service`

A standalone Spring AI service that wraps the EventConductor MCP tools behind a chat API.
Lets you operate the workflow engine in natural language via Claude or any other LLM.

See [ia-agent-service](/guides/ia-agent-service/) for the full setup guide.

---

## Quick-start matrix

| Demo | Mode | Persistence | HTTP | External deps |
|---|---|---|---|---|
| `workflow-embedded` | embedded | memory | ✓ (8080) | none |
| `workflow-embedded-headless` | embedded | memory | — | none |
| `workflow-embedded-db-headless` | embedded | jpa | — | none (H2) |
| `workflow-embedded-mvc` | embedded | memory | ✓ (8080) | none |
| `workflow-embedded-git` | embedded | jpa | ✓ (8090) | none (H2) + GitHub |
| `booking-service` | kafka | jpa | ✓ | Kafka + PostgreSQL |
| `content-service` | kafka | jpa | ✓ | Kafka + PostgreSQL |
| `users-service` | kafka | jpa | ✓ | Kafka + PostgreSQL |
| `api-gw` | — | — | ✓ | — |
| `control-plane-service` | kafka | jpa | ✓ | Kafka + PostgreSQL + Cloudflare |
| `ia-agent-service` | — | — | ✓ | Anthropic API + MCP server |
