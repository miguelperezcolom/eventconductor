---
name: eventconductor-scaffold
description: Scaffold and wire an EventConductor app — pick the deployment topology (embedded/memory, embedded/jpa, kafka/jpa), add the right Maven dependencies, choose the main class (@WorkflowEmbeddedApplication vs @SpringBootApplication), set workflow.mode / workflow.persistence, and place workflow definitions so they load. Use when starting a new orchestration app/service or fixing "my workflow definition isn't picked up / Kafka or JPA autoconfig fails / the engine beans aren't created". Triggers on new EventConductor project, workflow-engine dependency, WorkflowEmbeddedApplication, workflow.mode, workflow.persistence, classpath:/workflows.
---

# Scaffolding an EventConductor app

The steps definition is the easy part — the fiddly part is **wiring the deployment topology**
so the right beans exist and autoconfiguration doesn't fight you.

## 1. Pick the topology (two independent properties)

| Goal | `workflow.mode` | `workflow.persistence` | External deps |
|---|---|---|---|
| tests / local / embed in an app | `embedded` (default) | `memory` (default) | none |
| single node, durable state | `embedded` | `jpa` | a database |
| distributed, multi-pod | `kafka` | `jpa` | Kafka + PostgreSQL |

Defaults are `embedded` + `memory`. **Start there and grow.** Set the properties explicitly
only when you need JPA/Kafka.

## 2. Dependencies

```xml
<dependency>
  <groupId>io.mateu.workflow</groupId>
  <artifactId>workflow-engine</artifactId>
  <version>1.0-beta.010</version> <!-- check Maven Central / CHANGELOG.md for the newest release -->
</dependency>
<!-- add only if you use USER_TASK / human forms -->
<dependency>
  <groupId>io.mateu.workflow</groupId>
  <artifactId>forms-engine</artifactId>
  <version>1.0-beta.010</version>
</dependency>
```
Kafka mode uses `spring-cloud-starter-stream-kafka` — it already arrives transitively as a
non-optional dependency of `workflow-engine`, so you normally don't add it yourself. JPA mode
needs `spring-boot-starter-data-jpa` + a JDBC driver.

## 3. Main class

- **Embedded mode:** annotate the app with `@WorkflowEmbeddedApplication` (excludes the
  web/UI/JPA layers from scanning; Kafka & JPA autoconfig are excluded automatically). For
  `embedded`+`jpa` also add BOTH `@EnableJpaRepositories(basePackages = "io.mateu.workflow")`
  AND `@AutoConfigurationPackage(basePackages = "io.mateu.workflow")` (see
  `testbench/workflow-embedded-db-headless`'s `EmbeddedDbHeadlessApplication`).
- **Kafka mode:** a normal `@SpringBootApplication`.

```java
@WorkflowEmbeddedApplication
public class App {
    public static void main(String[] a) { SpringApplication.run(App.class, a); }
}
```

## 4. Configuration

```properties
# embedded + memory (defaults — can be omitted)
workflow.mode=embedded
workflow.persistence=memory
```
```properties
# embedded + jpa
workflow.mode=embedded
workflow.persistence=jpa
spring.datasource.url=jdbc:postgresql://localhost:5432/workflow
spring.datasource.username=workflow
spring.datasource.password=secret
spring.jpa.hibernate.ddl-auto=update
```
```properties
# kafka + jpa
workflow.mode=kafka
workflow.persistence=jpa
spring.kafka.bootstrap-servers=localhost:9092
spring.cloud.stream.bindings.consumeOutbox-in-0.destination=outbox
spring.cloud.stream.bindings.consumeUpstream-in-0.destination=upstream
spring.cloud.stream.bindings.consumeWorkerEvent-in-0.destination=downstream
spring.cloud.stream.function.definition=consumeOutbox;consumeUpstream;consumeWorkerEvent
# + datasource as above
```
Standalone distributed apps read `WORKFLOW_MODE` / `WORKFLOW_PERSISTENCE` env vars.

**Forms and rules persist separately:** `workflow.persistence` only covers the workflow engine.
If you use `forms-engine` or `rule-engine`, also set `forms.persistence=jpa` /
`rules.persistence=jpa` (both default to `memory`) — otherwise a JPA scaffold silently keeps
forms/rules in memory.

## 5. Place your definitions and workers

- Put `.json` / `.yaml` definitions under `src/main/resources/workflows/`
  (loaded from `classpath:/workflows/` at startup; in `jpa` mode also importable from Git).
- Implement workers — embedded: an `EmbeddedTaskExecutor` bean; Kafka: a Cloud Stream
  consumer. See the `eventconductor` skill.

## Golden rules (why nothing runs)

- **No engine beans / autoconfig errors in embedded mode** → you used `@SpringBootApplication`
  instead of `@WorkflowEmbeddedApplication`, or set `mode=kafka` without a broker. Use the
  embedded annotation; leave `mode`/`persistence` at defaults for a no-deps app.
- **Definition not picked up** → it isn't under `classpath:/workflows/`, or it doesn't parse
  (bad JSON/YAML, violated invariants — check the startup log). The classpath loader imports
  every parseable file regardless of `status`; the `name`+`steps` minimum applies only to the
  Git importer.
- **JPA errors with `memory`** → don't add a datasource for `memory` persistence; and don't
  set `persistence=jpa` without a configured datasource.

## Reference

Working skeletons live in `testbench/` (`workflow-embedded-headless`,
`workflow-embedded-db-headless`) and `apps/` (the standalone apps). Full config reference:
`doc/src/content/docs/reference/configuration.md` and `guides/deployment-modes.md`.
