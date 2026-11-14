# Chapter 14 — facts checked

| Claim | Source |
|---|---|
| Since 2.27/2.28 the engine refuses what it was not told to allow; one page lists every switch | docs/guides/security-defaults.md:6, :127-139 — **page only on main** (CL [Unreleased] → Docs); flagged on screen |
| Demos turn on `allow-anonymous` and `trust-forwarded-token`; copy their definitions, not those lines | security-defaults.md:137-139; SA application.properties:22-25, :34 |
| Anonymous API request → 401 | CL 2.27.0 (:428); real run (`Missing or invalid X-Api-Key header`, because the recording sets an API key) |
| MCP refuses too (403) and says how to open it | CL 2.28.0 (:366); `ConsoleAccess.java:42-45`; real run |
| With the API key the request is answered | `EngineApiAccess.java:22`; real run |
| Console without Spring Security does not open (403 page) | CL 2.28.0 (:366); real browser |
| `${secret:NAME}` only a reference, resolved at call time, only from `workflow.http.secrets`; environment not consulted | CL 2.27.0 (:427); security-defaults.md:70-72; real run: `[non-retryable] HTTP_CONFIG: Secret 'PARTNER_TOKEN' is not configured …` |
| Observability opt-in; metrics need a MeterRegistry (Actuator + Prometheus); metric names `eventconductor_*` | security-defaults.md:121-125; docs/reference/observability.md:14-16, :45-80 |
| Dashboard and compose come with the series app; Prometheus scrapes every 5 s | SA observability/prometheus.yml; SA README (Unreleased, on main) |
| Tracing off by default (sampling 0.0); the app's `observability` profile turns it on, OTLP | observability.md:221; SA application.properties:68-69, application-observability.properties |
| Each process a trace with a span per step | recorded Jaeger trace (process span + step spans, tags `eventconductor.process.businessKey`) |
| On by default: refusals, secrets by name, credentials bound to hosts, unsigned Git webhooks refused | CL 2.27.0, 2.28.0 (:362, :363, :366) |
| Opt-in: tracing, OTLP metrics, MCP, sensitive-variable encryption, sharding, process index, analytics rollups | observability.md:392-397; security-defaults.md:47, :105; guides/sharding.md:11; guides/process-index.md:11; reference/configuration.md:51 |
