---
name: eventconductor-scaffold
description: Scaffold and wire an EventConductor app — create the Maven project (pom with workflow-engine, workflow-test, the workflow-maven-plugin), pick the deployment topology (embedded/memory, embedded/jpa, kafka/jpa), choose the main class (@WorkflowEmbeddedApplication vs @SpringBootApplication, @EnableWorkflowUi), set workflow.mode / workflow.persistence and the secure-by-default access settings, and place definitions so they load. Use when starting a new orchestration app/service or fixing "my workflow definition isn't picked up / Kafka or JPA autoconfig fails / the engine beans aren't created / 401 or 403 from the engine". Triggers on new EventConductor project, workflow-engine dependency, WorkflowEmbeddedApplication, workflow.mode, workflow.persistence, classpath:/workflows, allow-anonymous.
---

# Scaffolding an EventConductor app

There is no archetype: a project is a Spring Boot 4 app (Java 21+) with the engine jar, the test
kit and the validating Maven plugin. Copy the files below, then grow the topology.

## 1. `pom.xml` (embedded + memory, with tests and build-time validation)

Use the newest `io.mateu.workflow` release on Maven Central (2.33.0 at the time of writing; all
artifacts share one version — if a fresh release is not on Central yet, take the `<release>` of
`https://repo1.maven.org/maven2/io/mateu/workflow/workflow-engine/maven-metadata.xml`). Spring Boot 4.1.x, Java 21.

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>
    <relativePath/>
  </parent>
  <groupId>com.example</groupId>
  <artifactId>orders</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <properties>
    <java.version>21</java.version>
    <eventconductor.version>2.33.0</eventconductor.version>
  </properties>
  <dependencies>
    <dependency>
      <groupId>io.mateu.workflow</groupId>
      <artifactId>workflow-engine</artifactId>
      <version>${eventconductor.version}</version>
    </dependency>
    <dependency>  <!-- JUnit 5 + Spring test + AssertJ + the kit -->
      <groupId>io.mateu.workflow</groupId>
      <artifactId>workflow-test</artifactId>
      <version>${eventconductor.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
      <plugin>  <!-- validates src/main/resources/{workflows,forms,rules,tasks} at process-resources -->
        <groupId>io.mateu.workflow</groupId>
        <artifactId>workflow-maven-plugin</artifactId>
        <version>${eventconductor.version}</version>
        <executions>
          <execution><goals><goal>validate</goal></goals></execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

Add only what you use:

| You use | Add |
|---|---|
| the REST API, admin UI or MCP (anything over HTTP) | `org.springframework.boot:spring-boot-starter-webmvc` (optional in workflow-engine) |
| `USER_TASK` (forms, task inbox) | `io.mateu.workflow:forms-engine` |
| `RULE` steps | `io.mateu.workflow:rule-engine` (catalog) and/or `rule-runtime` |
| `HTTP_CALL` in-process | `io.mateu.workflow:worker-http` + `worker-embedded` (then write ACTION workers as `TaskHandler`s, not a hand-written `EmbeddedTaskExecutor`, which would take the HTTP tasks too) |
| `LLM_CALL` / `AGENT_CALL` in-process | `io.mateu.workflow:worker-llm` + `worker-embedded` + `org.springframework.ai:spring-ai-anthropic` and/or `spring-ai-openai` (both optional in worker-llm) |
| typed task handlers (`TaskHandler`, task contracts) in-process | `io.mateu.workflow:worker-embedded` |
| `workflow.persistence=jpa` | `org.flywaydb:flyway-core` (+ `flyway-database-postgresql`) and a JDBC driver |
| `workflow.mode=kafka` + event `triggers` / `onEvent` | `io.mateu.workflow:inbound-events-kafka` |

Without `spring-boot-starter-webmvc` the app is headless: processes run, nothing is served.

## 2. Main class

```java
package com.example.orders;

import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import org.springframework.boot.SpringApplication;

@WorkflowEmbeddedApplication          // NOT @SpringBootApplication in embedded mode
public class OrdersApplication {
    public static void main(String[] args) { SpringApplication.run(OrdersApplication.class, args); }
}
```

- It scans `io.mateu.workflow` (minus the UI layer) and your app's own package. Do not put a
  `@ComponentScan` on it — that replaces its scans; put extra scans on an imported class.
- Showing the engine's admin UI inside your own Mateu UI (`WorkflowMenu`/`FormsMenu`/`RulesMenu`):
  add `@EnableWorkflowUi` (`io.mateu.workflow.autoconfigure`) — required since 2.28.
- `embedded` + `jpa`: also
  `@EnableJpaRepositories(basePackages = "io.mateu.workflow.infra.out.persistence")` and
  `@AutoConfigurationPackage(basePackages = "io.mateu.workflow.infra.out.persistence")`.
- `kafka` mode: a normal `@SpringBootApplication` (or run `apps/orchestrator-standalone-app`).

## 3. `src/main/resources/application.properties`

```properties
# embedded + memory are the defaults; written out for clarity
workflow.mode=embedded
workflow.persistence=memory
# Secure by default (2.27/2.28): without Spring Security the REST API answers 401 and the
# admin UI / MCP 403. For LOCAL development only:
workflow.message-api.allow-anonymous=true
```
Reachable on a network: add Spring Security (any `SecurityFilterChain` bean) or set
`workflow.message-api.api-key=...` (callers send `X-Api-Key`) and `workflow.ui.enabled=false`.
See the `eventconductor` skill, `reference/security.md`.

Durable single node (`embedded` + `jpa`):
```properties
workflow.persistence=jpa
spring.datasource.url=jdbc:postgresql://localhost:5432/workflow
spring.datasource.username=workflow
spring.datasource.password=secret
# the engine runs its own Flyway migrations (needs flyway-core); let them own the schema
spring.jpa.hibernate.ddl-auto=validate
```

Distributed (`kafka` + `jpa`): datasource as above plus
```properties
workflow.mode=kafka
spring.kafka.bootstrap-servers=localhost:9092
# the functions this app composes: the engine's two (+ consumeWorkerEvent if a worker/forms engine runs here)
spring.cloud.function.definition=consumeOutbox;consumeUpstream
```
The engine contributes its bindings (destinations, a consumer group each, `batch-mode=true`, the
redelivering error handler) as lowest-precedence defaults — but not `spring.cloud.function.definition`,
which you must set. Usually you just run the standalone images (`eventconductor-run` skill).

Forms and rules persist separately: `forms.persistence=jpa` / `rules.persistence=jpa` (default `memory`).

## 4. Layout

```
src/main/resources/workflows/order.workflow.yaml   # definitions (.yaml/.yml/.json/.ec; *.workflow.yaml and *.ec.yaml open in the IDE plugins)
src/main/resources/workflows/events.yaml           # optional event triggers (never read as a definition)
src/main/resources/workflows/cron.yaml             # optional schedules (never read as a definition)
forms/expense-approval.ecform                      # USER_TASK forms: NOT read from the classpath —
                                                   # forms.directory-import.directories=forms (or git import)
src/main/resources/tasks/                          # .ectask task contracts (optional)
src/test/java/.../OrderWorkflowTest.java           # @EventConductorTest
```
Write the definition with the `eventconductor` skill (`reference/patterns.md`), a test with
`reference/testing.md`, and a worker with `reference/workers.md`.

## 5. Build loop

```bash
mvn eventconductor:validate     # prefix of workflow-maven-plugin; schema + engine rules (also runs in mvn verify)
mvn test                        # @EventConductorTest runs your definitions in memory, time skipping
mvn spring-boot:run             # with webmvc: http://localhost:8080/workflow/api/**
```

Normal noise, safe to ignore: `Unknown keyword $defs` / `Unknown keyword deprecated` (schema
validator), `No task contracts found at classpath:/tasks/`, Mateu's "found no UI to serve" in a
headless app (its paragraph about annotation processors needs no action), `MemoryPersistenceWarning`
with `workflow.persistence=memory`. Validation messages come in the JVM's language; `MAVEN_OPTS=-Duser.language=en` for English.

## Why nothing runs

- **Engine beans missing / autoconfig errors** → `@SpringBootApplication` used in embedded mode, or
  `mode=kafka` without a broker. Use `@WorkflowEmbeddedApplication`; keep the defaults.
- **Definition not picked up** → not under `classpath:/workflows/`, or it does not parse / violates
  an invariant (the startup log says why; `mvn eventconductor:validate` says it at build time).
- **401 on `/workflow/api/**`, 403 on `/_workflow` or MCP** → secure defaults; see step 3.
- **JPA errors with `memory`** → don't configure a datasource for memory; don't set
  `persistence=jpa` without one (and without `flyway-core` the engine warns it runs index-less).
- **Event triggers ignored in kafka mode** → `inbound-events-kafka` missing.
- **UI missing after an upgrade to ≥ 2.28** → add `@EnableWorkflowUi`.

Working skeletons (in https://github.com/miguelperezcolom/eventconductor): `testbench/workflow-embedded-headless`,
`testbench/workflow-embedded-db-headless`, `apps/*-standalone-app`. Config reference:
`doc/src/content/docs/reference/configuration.md`, `guides/deployment-modes.md` there.
