# Observability

EventConductor exposes **metrics** and **distributed tracing** through
[Micrometer](https://micrometer.io/). Both are *optional and host-activated*: the engine
libraries carry no hard observability dependency and behave as no-ops until the host application
provides the relevant infrastructure (a `MeterRegistry` for metrics, an `ObservationRegistry` +
tracer for tracing). The three standalone apps (`orchestrator`, `forms`, `rule`) wire both.

## Design

Each engine defines a metrics **port** in `application/out` with no-op default methods and a
`NOOP` instance. A Micrometer implementation lives in the `autoconfigure` package and is only
created by an autoconfiguration guarded on `@ConditionalOnClass(MeterRegistry)` +
`@ConditionalOnBean(MeterRegistry)`. When no `MeterRegistry` is present the engine autoconfig
falls back to the `NOOP` port. Instrumentation is done at the **use-case layer**, so the same
signals are emitted in every deployment mode (embedded+memory, embedded+jpa, kafka+jpa).

## Metrics

All meters are prefixed `eventconductor.`. Enable them by adding Spring Boot Actuator + a registry
(the standalone apps already include `micrometer-registry-prometheus`).

### Workflow engine
| Meter | Type | Tags |
|-------|------|------|
| `eventconductor.process.started` | counter | `workflowDefinitionId` |
| `eventconductor.process.completed` / `.errored` / `.cancelled` | counter | `workflowDefinitionId` |
| `eventconductor.process.duration` | timer | `workflowDefinitionId`, `outcome` |
| `eventconductor.process.running` | gauge | – |
| `eventconductor.step.executions` | counter | `workflowDefinitionId`, `outcome` |
| `eventconductor.step.duration` | timer | `workflowDefinitionId`, `outcome` |
| `eventconductor.step.retries` | counter | `workflowDefinitionId`, `trigger` |
| `eventconductor.step.compensations` | counter | `workflowDefinitionId` |
| `eventconductor.outbox.pending` | gauge (JPA only) | – |

### Forms engine
| Meter | Type | Tags |
|-------|------|------|
| `eventconductor.forms.task.created` | counter | `formId` |
| `eventconductor.forms.task.completed` / `.cancelled` | counter | `formId` |
| `eventconductor.forms.task.duration` | timer | `formId`, `outcome` |
| `eventconductor.forms.imported` | counter | – |

### Rule engine (catalog)
| Meter | Type | Tags |
|-------|------|------|
| `eventconductor.rule.catalog.saved` / `.deleted` | counter | `ruleId` |
| `eventconductor.rule.catalog.imported` | counter | – |
| `eventconductor.rule.catalog.served` | counter | `ruleId`, `source` |

### Rule runtime (evaluation)
| Meter | Type | Tags |
|-------|------|------|
| `eventconductor.rule.evaluation.count` | counter | `ruleId`, `ruleType`, `outcome` (`matched`/`nomatch`/`error`) |
| `eventconductor.rule.evaluation.duration` | timer | `ruleId`, `ruleType` |
| `eventconductor.rule.evaluation.cache` | counter | `ruleId`, `result` (`hit`/`miss`) |

### Scraping with Prometheus
The standalone apps expose the Actuator Prometheus endpoint:

```
management.endpoints.web.exposure.include: health,info,prometheus,metrics
```

Scrape `GET /actuator/prometheus`.

## Distributed tracing (OpenTelemetry / OTLP)

The standalone apps bundle `micrometer-tracing-bridge-otel` and `opentelemetry-exporter-otlp`.
Tracing is **disabled by default** (sampling `0.0`) so there is no overhead until you opt in:

```bash
export TRACING_SAMPLING=1.0                                   # fraction of traces to sample (0..1)
export OTLP_TRACING_ENDPOINT=http://localhost:4318/v1/traces  # your OTel collector / Tempo / Jaeger
```

With tracing on, HTTP requests, Kafka (Spring Cloud Stream) publish/consume and JDBC calls are
auto-instrumented and the trace context propagates across the engines' asynchronous boundaries, so
a single process execution can be followed end to end across services.

> Engine spans come from framework auto-instrumentation rather than hand-written spans in the
> use-cases: because Micrometer is an *optional* engine dependency, importing tracing types into
> core services would break the "runs without Micrometer" guarantee. Custom domain spans can be
> added later behind an optional port if finer-grained business traces are needed.
