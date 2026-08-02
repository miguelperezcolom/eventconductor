---
title: Observability
description: Metrics (Micrometer/Prometheus) and distributed tracing (OpenTelemetry/OTLP) for every EventConductor engine.
---

EventConductor exposes **metrics** and **distributed tracing** through
[Micrometer](https://micrometer.io/). Both are *optional and host-activated*: the engine libraries
carry no hard observability dependency and behave as no-ops until the host application provides the
relevant infrastructure (a `MeterRegistry` for metrics, a tracer for tracing). The `orchestrator`,
`forms` and `rule` standalone apps wire both.

## Metrics

All engines (workflow, forms, rule catalog and rule runtime) publish engine-level metrics through
Micrometer. They activate automatically when a `MeterRegistry` bean is present in the host
application — no property is needed. The easiest way to get one (plus a Prometheus scrape endpoint)
is:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

```properties
management.endpoints.web.exposure.include=health,prometheus
```

Metrics are then available at `GET /actuator/prometheus`. Without a `MeterRegistry` each engine
falls back to a no-op implementation with zero overhead — Micrometer is an optional dependency of
every engine module.

Instrumentation happens at the use-case layer, so the same metrics are emitted in **all deployment
modes** (embedded + memory, embedded + jpa, kafka + jpa).

### Workflow engine

| Metric | Type | Tags | Description |
|---|---|---|---|
| `eventconductor.process.started` | Counter | `workflowDefinitionId` | Processes started |
| `eventconductor.process.completed` | Counter | `workflowDefinitionId` | Processes completed successfully |
| `eventconductor.process.errored` | Counter | `workflowDefinitionId` | Processes finished in error (retries exhausted) |
| `eventconductor.process.cancelled` | Counter | `workflowDefinitionId` | Processes cancelled |
| `eventconductor.process.duration` | Timer | `workflowDefinitionId`, `outcome` | Process duration from start to final status (`COMPLETED` \| `ERROR` \| `CANCELLED`) |
| `eventconductor.step.executions` | Counter | `workflowDefinitionId`, `outcome` | Step executions finished, by outcome (`COMPLETED` \| `ERROR` \| `TIMEOUT`) |
| `eventconductor.step.duration` | Timer | `workflowDefinitionId`, `outcome` | Step execution duration, from dispatch to final status |
| `eventconductor.step.retries` | Counter | `workflowDefinitionId`, `trigger` | Retries performed (`auto` = retry policy, `manual` = user-initiated) |
| `eventconductor.step.compensations` | Counter | `workflowDefinitionId` | Compensation steps triggered after retries were exhausted |
| `eventconductor.process.running` | Gauge | — | Processes currently in `RUNNING` status |
| `eventconductor.outbox.pending` | Gauge | — | Outbox messages waiting to be relayed (only with `workflow.persistence=jpa`; the in-memory mode has no outbox). Rises during a broker outage and must return to zero |
| `eventconductor.steps.stalled` | Gauge | — | Live steps with **no deadline** that have been waiting longer than `workflow.stalled-step-after-ms`. The one gauge to alert on — see below |

**Alert on `eventconductor.steps.stalled`.** Every other metric here counts something happening;
this counts work that has stopped happening where nothing in the engine will notice. A step that
declares no timeout has no deadline, and the timeout scan is an index range over the deadline — so
if that step's dispatch or its worker's reply is lost, the process stops permanently and silently.
Any sustained non-zero value is work that will never finish. Give those steps a timeout, or set
`workflow.default-step-timeout-ms`. See [Reliability](/guides/reliability/).

### Forms engine

| Metric | Type | Tags | Description |
|---|---|---|---|
| `eventconductor.forms.task.created` | Counter | `formId` | User tasks created |
| `eventconductor.forms.task.completed` | Counter | `formId` | User tasks completed |
| `eventconductor.forms.task.cancelled` | Counter | `formId` | User tasks cancelled |
| `eventconductor.forms.task.duration` | Timer | `formId`, `outcome` | Task duration (recorded only when the form execution carries usable timestamps) |
| `eventconductor.forms.imported` | Counter | — | Form definitions imported (e.g. from Git) |

### Rule engine (catalog)

| Metric | Type | Tags | Description |
|---|---|---|---|
| `eventconductor.rule.catalog.saved` | Counter | `ruleId` | Rules created/updated in the catalog |
| `eventconductor.rule.catalog.deleted` | Counter | `ruleId` | Rules deleted |
| `eventconductor.rule.catalog.imported` | Counter | — | Rules imported (e.g. from Git) |
| `eventconductor.rule.catalog.served` | Counter | `ruleId`, `source` | Rules served to the runtime (e.g. `source=grpc`) |

### Rule runtime (evaluation)

| Metric | Type | Tags | Description |
|---|---|---|---|
| `eventconductor.rule.evaluation.count` | Counter | `ruleId`, `ruleType`, `outcome` | Rule evaluations, by outcome (`matched` \| `nomatch` \| `error`) |
| `eventconductor.rule.evaluation.duration` | Timer | `ruleId`, `ruleType` | Evaluation duration |
| `eventconductor.rule.evaluation.cache` | Counter | `ruleId`, `result` | Rule-source cache lookups (`result=hit` \| `miss`) |

The forms, rule-catalog and rule-runtime metrics follow the same activation rules as the workflow
metrics above (a `MeterRegistry` bean enables them; otherwise a zero-overhead no-op is used). The
rule runtime also works as a plain, non-Spring library — its metrics port then defaults to the
no-op.

### Notes

- With the Prometheus registry, names are exported in Prometheus form: `eventconductor.process.started` becomes `eventconductor_process_started_total`, timers become `_seconds_count` / `_seconds_sum` / `_seconds_max` families.
- Each step retry that fails again increments `eventconductor.step.executions{outcome="ERROR"}`, so the counter reflects attempts, not distinct steps.
- Counters are per-node and reset on restart, as usual with Prometheus counters — use `rate()`/`increase()` over them.
- To customize (percentile histograms, common tags, renaming), use standard Micrometer `MeterFilter` beans, or replace the implementation entirely by defining your own metrics bean (e.g. `WorkflowMetrics`).

## Distributed tracing

The `orchestrator`, `forms` and `rule` standalone apps ship distributed tracing over
[OpenTelemetry](https://opentelemetry.io/) (OTLP). It is **disabled by default** (sampling `0.0`)
so there is no overhead until you opt in — set the sampling probability and point it at your
collector:

```properties
# fraction of traces to sample, 0.0 (off) .. 1.0 (all)
management.tracing.sampling.probability=${TRACING_SAMPLING:0.0}
# OTLP HTTP traces endpoint (OTel Collector / Tempo / Jaeger)
management.otlp.tracing.endpoint=${OTLP_TRACING_ENDPOINT:http://localhost:4318/v1/traces}
```

```bash
export TRACING_SAMPLING=1.0
export OTLP_TRACING_ENDPOINT=http://localhost:4318/v1/traces
```

With tracing on, HTTP requests, Kafka (Spring Cloud Stream) publish/consume and JDBC calls are
auto-instrumented and the trace context propagates across the engines' asynchronous boundaries, so
a single process execution can be followed end to end across services. The standalone apps bundle
`micrometer-tracing-bridge-otel` and `opentelemetry-exporter-otlp`; enabling tracing needs only the
two properties above.

Tracing is wired at the application layer (rather than as hand-written spans inside the engines)
because Micrometer is an *optional* engine dependency — this keeps the engine libraries runnable
with zero observability dependencies, consistent with how metrics activate.
