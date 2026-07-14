---
title: Rule Evaluation
description: Embedding the EventConductor rule runtime — local, classpath, REST or gRPC rule sources, Kafka cache refresh, and the RULE workflow step.
---

The **rule runtime** (`io.mateu.workflow:rule-runtime`) is a lightweight, embeddable library that evaluates rules where your data lives. It has no UI-framework dependency, Spring is optional, and gRPC support is optional — you only pull what you use.

## Evaluating rules

```java
RuleEvaluationResult result = ruleEvaluator.evaluate("high-value-order", Map.of(
        "order", Map.of("total", 200),
        "customer", Map.of("category", "VIP")));
result.matched();   // true
result.outputs();   // {discount=20.0, approvalRequired=true}
```

- `evaluate(Rule, facts)` — evaluate a rule you already have (no source needed).
- `evaluate(ruleId, facts)` — fetch from the configured `RuleSource` and evaluate.
- `evaluateByTag(tag, facts)` — evaluate every rule with the tag, highest `salience` first; each rule sees the outputs accumulated so far (lightweight forward chaining).

Facts are plain maps. When facts arrive as workflow variables (`String`/`String`), `FactCoercer` turns them into typed facts: numbers, booleans and JSON payloads are coerced, and dotted names (`order.total`) become nested maps.

## Where rules come from: `rules.source`

| `rules.source` | What it does | Extra properties |
|---|---|---|
| `local` (default) | Reads the catalog repository in the same JVM (rule-engine on the classpath). | — |
| `classpath` | Loads `classpath:/rules/*.{json,yaml,yml}` at startup. | — |
| `rest` | Pulls from the catalog's REST API, cached. | `rules.catalog.url` |
| `grpc` | Pulls from the catalog's gRPC API, cached. | `rules.catalog.grpc-target` (e.g. `localhost:9090`) |

Remote sources (`rest`, `grpc`) are wrapped in a cache with `rules.cache.ttl` (default `PT5M`; `PT0S` = never expires). For gRPC the embedder must add the gRPC stack (`grpc-netty-shaded`, `grpc-stub`, `grpc-protobuf`) — the runtime declares it optional.

**Kafka cache refresh:** set `rules.kafka-refresh=true` and bind `consumeRuleCatalogEvent-in-0` to the `rules` destination. The catalog's `RulePublished` events carry the rule's canonical JSON, so caches update without a fetch; `RuleDeleted` evicts.

```yaml
rules:
  source: grpc
  catalog:
    grpc-target: localhost:9090
  cache:
    ttl: PT0S          # rely on kafka refresh only
  kafka-refresh: true

spring:
  cloud:
    function:
      definition: consumeRuleCatalogEvent
    stream:
      bindings:
        consumeRuleCatalogEvent-in-0:
          destination: rules
```

## The RULE workflow step

Workflow definitions can evaluate a rule as a step, mirroring how `USER_TASK` uses `formId`:

```yaml
steps:
  - id: apply-discount
    type: RULE
    name: Apply the discount rule
    ruleId: high-value-order
```

The engine dispatches a `TaskExecutionRequested` with `taskId=evaluate-rule` and a `ruleId` variable. The rule's outputs come back as process variables via `TaskStatusChanged`.

- **Kafka mode:** any app embedding rule-runtime acts as the rules worker — the `consumeWorkerEventForRuleRuntime` consumer (bound to `downstream`) evaluates and replies on `upstream`. `rule-standalone-app` does this out of the box.
- **Embedded mode:** route the task in your `EmbeddedTaskExecutor`:

```java
@Bean
public EmbeddedTaskExecutor taskExecutor(EvaluateRuleUseCase evaluateRule,
                                         FactCoercer coercer,
                                         UpdateStepExecutionUseCase update) {
    return request -> {
        if ("evaluate-rule".equals(request.taskId())) {
            var ruleId = request.variables().stream()
                    .filter(v -> "ruleId".equals(v.name())).findFirst().orElseThrow().value();
            var result = evaluateRule.handle(
                    new EvaluateRuleCommand(ruleId, coercer.toFacts(request.variables())));
            // report result.outputs() back via UpdateStepExecutionUseCase …
        }
    };
}
```

See `testbench/rules-embedded-mvc` for the complete embedded example, and `testbench/rules-remote-client` for a runtime-only app fetching rules over gRPC.

## Property reference

| Property | Default | Description |
|---|---|---|
| `rules.persistence` | `memory` | Catalog storage: `memory` or `jpa` (rule-engine). |
| `rules.source` | `local` | Runtime source: `local`, `classpath`, `rest`, `grpc`. |
| `rules.catalog.url` | — | Catalog base URL for `rest`. |
| `rules.catalog.grpc-target` | — | Catalog `host:port` for `grpc`. |
| `rules.cache.ttl` | `PT5M` | Remote-source cache TTL; `PT0S` never expires. |
| `rules.kafka-refresh` | `false` | Update the cache from `RulePublished`/`RuleDeleted` events. |
| `rules.grpc.enabled` | `false` | Start the catalog's gRPC server (rule-engine). |
| `rules.grpc.port` | `9090` | Catalog gRPC port. |
| `rules.git-import.*` | — | Git repositories + webhook secret (rule-engine). |
