---
title: Demo Applications
description: Overview of all runnable demo applications included in the EventConductor repository.
---

The `demo/` directory contains runnable applications illustrating distributed deployment patterns.
The `testbench/` directory contains self-contained smoke-test apps for both engines in embedded mode — no Kafka or external database required unless noted.

Each app can be started with `mvn spring-boot:run` from its own directory.

## Testbench — workflow engine

These apps run the **workflow engine** in-process — no Kafka broker required.

### `workflow-embedded`

**Mode:** `embedded` + `memory` | **Port:** 8080

The simplest possible demo. The engine runs fully in-process with no external dependencies.
Includes the web UI, the MCP endpoint, and a single "hello-world" workflow executed at startup.

```bash
cd testbench/workflow-embedded && mvn spring-boot:run
```

Workflow definitions are loaded from `src/main/resources/workflows/`.

---

### `workflow-embedded-headless`

**Mode:** `embedded` + `memory` | **No HTTP server**

Same as `embedded` but with no web server — just a pure background JVM process. Useful for
showing how to embed the engine as a library inside an existing application without opening
any port.

```bash
cd testbench/workflow-embedded-headless && mvn spring-boot:run
```

---

### `workflow-embedded-db-headless`

**Mode:** `embedded` + `jpa` (H2 in-memory) | **No HTTP server**

Embedded engine with JPA persistence via H2. State survives restarts within the same JVM
session. Shows how to add a real database without switching to Kafka.

```bash
cd testbench/workflow-embedded-db-headless && mvn spring-boot:run
```

---

### `workflow-embedded-mvc`

**Mode:** `embedded` + `memory` | **Port:** 8090

Embedded engine exposed as a plain Spring MVC REST API — no MCP, no UI. Illustrates how to
wrap the engine with your own REST layer when you don't want to use the built-in UI or MCP
tools.

Endpoints:
- `POST /processes` — start a process instance
- `GET /processes/{id}` — get process state

```bash
cd testbench/workflow-embedded-mvc && mvn spring-boot:run
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
cd testbench/workflow-embedded-git && mvn spring-boot:run
# → imports 23 workflow definitions from GitHub
# → UI available at http://localhost:8090
# → webhook at POST http://localhost:8090/workflow/webhooks/github
```

See [Importing from Git](/guides/workflow-definitions/#importing-from-git) for configuration details.

---

## Testbench — forms engine

These apps run the **forms engine** in-process — no Kafka broker required.

### `forms-embedded`

**Mode:** `embedded` + `memory` | **Port:** 8091

Forms engine in memory mode with the web UI and a sample form created at startup.

```bash
cd testbench/forms-embedded && mvn spring-boot:run
```

---

### `forms-embedded-headless`

**Mode:** `embedded` + `memory` | **No HTTP server**

Forms engine in memory mode with no web server. Creates a form and a task execution at startup.

```bash
cd testbench/forms-embedded-headless && mvn spring-boot:run
```

---

### `forms-embedded-db-headless`

**Mode:** `embedded` + `jpa` (H2 in-memory) | **No HTTP server**

Forms engine with JPA persistence via H2. State survives restarts within the same JVM session.

```bash
cd testbench/forms-embedded-db-headless && mvn spring-boot:run
```

---

### `forms-embedded-mvc`

**Mode:** `embedded` + `memory` | **Port:** 8091

Forms engine exposed as a plain REST API. Endpoints:
- `POST /forms` — create a form definition
- `GET /forms/{id}` — get a form definition
- `POST /tasks` — create a task (FormExecution)
- `GET /tasks/{id}` — get a task

```bash
cd testbench/forms-embedded-mvc && mvn spring-boot:run
```

---

### `forms-embedded-git`

**Mode:** `embedded` + `jpa` (H2 in-memory) | **Port:** 8092

Forms engine with JPA persistence and form definitions imported from Git at startup.
Demonstrates the Git import feature and the GitHub webhook endpoint.

```bash
cd testbench/forms-embedded-git && mvn spring-boot:run
# → UI available at http://localhost:8092
# → webhook at POST http://localhost:8092/forms/webhooks/github
```

See [Importing from Git](/guides/form-definitions/#importing-from-git) for configuration details.

---

## Testbench — rule engine

These apps exercise the **rule engine / rule runtime** in-process — no broker required.

### `rules-embedded-headless`

**Rules source:** classpath, in-memory catalog | **No HTTP server**

Loads sample rule definitions from the classpath and evaluates them (expression rule and
decision table) at startup with the embedded `rule-runtime`.

```bash
cd testbench/rules-embedded-headless && mvn spring-boot:run
```

### `rules-embedded-mvc`

**Persistence:** `jpa` (H2 in-memory) | **Port:** 8093

Rule catalog backed by H2, with the rules web UI mounted at the root path.

```bash
cd testbench/rules-embedded-mvc && mvn spring-boot:run
```

### `rules-remote-client`

**Rules source:** remote catalog over gRPC (`localhost:9090`, switchable to REST) | **No HTTP server**

Only `rule-runtime` on the classpath: fetches rule definitions from a running remote catalog
(start `dev-app` or `rule-standalone-app` first), caches them locally (`rules.cache.ttl=PT5M`)
and evaluates them in-process.

```bash
cd testbench/rules-remote-client && mvn spring-boot:run
```

---

## Multi-service system

These demos form a realistic distributed system that showcases the full `kafka` + `jpa` mode.
They are designed to run together against the shared infrastructure defined in
`.dev/docker-compose.yml` — PostgreSQL plus a Redpanda broker exposed on **localhost:9192**
(the demo services' `application.yaml` files point there):

```bash
docker compose -f .dev/docker-compose.yml up -d
```

Note this is a different broker than the standalone distributed setup in
`apps/docker-compose.yml`, whose Redpanda listens on the conventional **9092** — use that
file instead if you want the published orchestrator/forms/worker containers rather than
the demo services.

Default ports:

| Service | Port |
|---|---|
| `api-gw` | 8191 |
| `ia-agent-service` | 8095 |
| `shell` | 8101 |
| `users-service` | 8102 |
| `control-plane-service` | 8103 |
| `content-service` | 8104 |
| `static-content-server` | 8107 |
| `booking-service` | 8108 |

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

`static` is a plain directory of screenshot PNG assets — not a runnable application.
`static-content-server` is a small Spring Boot static resource server (port 8107) used by
the shell and other services; it has its own `pom.xml` outside the demo Maven reactor, so
run it with `mvn spring-boot:run` from its own directory.

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

### Workflow engine testbench

| Module | Mode | Persistence | HTTP | External deps |
|---|---|---|---|---|
| `workflow-embedded` | embedded | memory | ✓ (8080) | none |
| `workflow-embedded-headless` | embedded | memory | — | none |
| `workflow-embedded-db-headless` | embedded | jpa | — | none (H2) |
| `workflow-embedded-mvc` | embedded | memory | ✓ (8090) | none |
| `workflow-embedded-git` | embedded | jpa | ✓ (8090) | none (H2) + GitHub |

### Forms engine testbench

| Module | Mode | Persistence | HTTP | External deps |
|---|---|---|---|---|
| `forms-embedded` | embedded | memory | ✓ (8091) | none |
| `forms-embedded-headless` | embedded | memory | — | none |
| `forms-embedded-db-headless` | embedded | jpa | — | none (H2) |
| `forms-embedded-mvc` | embedded | memory | ✓ (8091) | none |
| `forms-embedded-git` | embedded | jpa | ✓ (8092) | none (H2) + GitHub |

### Rule engine testbench

| Module | Rules source | Persistence | HTTP | External deps |
|---|---|---|---|---|
| `rules-embedded-headless` | classpath | memory | — | none |
| `rules-embedded-mvc` | local catalog | jpa | ✓ (8093) | none (H2) |
| `rules-remote-client` | remote (gRPC/REST) | local cache | — | a running rule catalog |

### Demo applications

| Demo | Mode | Persistence | HTTP | External deps |
|---|---|---|---|---|
| `booking-service` | kafka | jpa | ✓ | Kafka + PostgreSQL |
| `content-service` | kafka | jpa | ✓ | Kafka + PostgreSQL |
| `users-service` | kafka | jpa | ✓ | Kafka + PostgreSQL |
| `api-gw` | — | — | ✓ | — |
| `control-plane-service` | kafka | jpa | ✓ | Kafka + PostgreSQL + Cloudflare |
| `ia-agent-service` | — | — | ✓ | Anthropic API + MCP server |
