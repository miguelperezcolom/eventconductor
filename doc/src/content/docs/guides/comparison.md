---
title: "Comparison: Camunda & Temporal"
description: An honest, in-depth comparison of EventConductor, Camunda 8, and Temporal — philosophy, architecture, licensing, human tasks, failure handling, and AI integration.
---

Camunda, Temporal, and EventConductor all orchestrate long-running business processes, but they start from very different philosophies:

- **Camunda 8** is a *BPMN platform*: processes are BPMN 2.0 diagrams executed by a dedicated engine (Zeebe), surrounded by a suite of tools (Modeler, Tasklist, Operate, Optimize).
- **Temporal** is *durable execution as code*: workflows are ordinary functions in your programming language, made fault-tolerant by an event-sourced replay engine running in a dedicated cluster.
- **EventConductor** is an *embeddable, event-driven engine with a declarative DSL*: workflows are flat JSON/YAML files owned by developers, executed by a Spring Boot library that scales from an in-memory unit test to a Kafka + PostgreSQL cluster.

None of the three is universally "better" — they optimize for different teams and constraints. This page compares them honestly, including where the alternatives are stronger. Facts about Camunda and Temporal are accurate as of **July 2026** (Camunda 8.8/8.9, current Temporal server and SDKs).

## At a glance

| | EventConductor | Camunda 8 | Temporal |
|---|---|---|---|
| **Workflow definition** | Declarative JSON/YAML DSL (flat file, PR-reviewable) | BPMN 2.0 XML diagrams (+ DMN for decisions) | Code (workflow functions in your language) |
| **Execution model** | Event-driven state machine (outbox pattern) | Token-based BPMN engine (Zeebe) | Durable execution via event-sourced replay |
| **Runs as** | Library embedded in your Spring Boot app | Dedicated cluster (Zeebe + Operate + Tasklist + Identity + Elasticsearch/OpenSearch) or SaaS | Dedicated cluster (server + DB, optional Elasticsearch) or Temporal Cloud |
| **Minimum footprint** | Zero dependencies (in-memory, in-process) | Docker Compose / Helm with several services | Local dev server (single binary); production needs a cluster |
| **Production infrastructure** | PostgreSQL (+ Kafka for distributed mode) | Full component stack, typically on Kubernetes | Temporal cluster + Cassandra/PostgreSQL/MySQL |
| **License** | MIT | Source-available (Camunda License); production self-managed requires a paid Enterprise license | MIT (server); Temporal Cloud is the paid SaaS |
| **Human tasks & forms** | Built-in forms engine + drag-and-drop form editor | Built-in (Tasklist + Camunda Forms) | Not built-in — model with signals/updates, build your own UI |
| **Visual designer** | Drag-and-drop workflow & form editors (persist back to JSON) | Best-in-class BPMN Modeler (desktop & web) | None (code is the source of truth) |
| **Operations UI** | Built-in management UI | Operate + Optimize | Temporal Web UI |
| **Retries / timeouts** | Declarative per step (`retries`, `timeout`) | BPMN error/timer events + job retries | Retry policies & timeouts in code (very fine-grained) |
| **Saga / compensation** | Declarative (`rollbackable` + `compensationStepId`, reverse-order execution) | BPMN compensation events | Coded manually (saga pattern in workflow code) |
| **Worker languages** | Java/Spring first-class; any language via Kafka in distributed mode | Polyglot job workers over gRPC (Java, Node, Go, ...) | Go, Java, TypeScript, Python, .NET, PHP, Ruby SDKs |
| **AI integration** | Native MCP servers in every module + bundled AI agent service | AI Agent connector, MCP client & A2A (8.8+), API MCP server | MCP server, OpenAI Agents SDK & Google ADK integrations |
| **Maturity & community** | Young open-source project | Very mature (company founded 2008, large ecosystem) | Mature (2019, from Uber's Cadence; large community) |
| **Learning curve** | Low (a JSON schema and a worker contract) | High (BPMN, DMN, component suite, operations) | Medium-high (determinism rules, replay semantics, versioning) |

## Philosophy: who owns the workflow?

**Camunda** is diagram-first. BPMN 2.0 is an ISO standard that business analysts can read, and the Camunda Modeler is genuinely excellent. If your organization already speaks BPMN, or process diagrams are a deliverable in themselves (compliance, documentation, business/IT alignment), Camunda is the natural choice. The cost is that the XML behind the diagram is not something developers enjoy hand-editing or code-reviewing, and executable diagrams need technical attributes (job types, I/O mappings) that make them developer artifacts anyway.

**Temporal** is code-first to the extreme: there is no definition artifact at all. A workflow *is* a function; the engine records every side effect and replays the function deterministically after a crash. This is enormously powerful — you get loops, conditionals, and abstractions in a real programming language with full type safety. The cost is that workflow code must obey strict determinism rules (no random numbers, no direct I/O, no iteration over unordered maps in some SDKs), versioning running workflows requires explicit patching APIs, and the workflow logic is only readable by programmers of that language.

**EventConductor** sits deliberately in the middle: the workflow is *data* (a flat JSON/YAML document), not a diagram and not code. It is expressive enough for retries, timeouts, compensation, parallel branches, sub-processes, conditional expressions, and human tasks — but it stays a single file that fits in a pull request, diffs cleanly, and can be edited either by hand or in the included drag-and-drop editor. Business logic never lives in the definition; it lives in your workers.

**Change management is where these philosophies bite.** Long-running processes mean there are always instances in flight when you deploy — for a booking or an onboarding that lives for weeks, versioning is not an edge case, it is part of every release. Engines that version definitions as *data* handle this structurally: in EventConductor, every process instance carries an **immutable snapshot of the definition it started with**, so redeploying a definition can never affect running instances, while new instances pick up the new version — no migration step, no code branches. Camunda likewise keeps versioned deployments (running instances stay on their version, with optional instance migration). Temporal, having no definition artifact, must version *code*: changing logic that touches in-flight workflows requires explicit patching APIs (`patched()` / `getVersion()`) or worker versioning, the workflow code accumulates per-version branches, and a mistake surfaces as a non-deterministic replay error. Teams run this successfully at scale, but it is recurring friction that the definition-as-data engines simply do not have.

## Architecture & deployment

**Camunda 8** is a distributed platform. Self-managed installations run Zeebe brokers and gateway, Operate, Tasklist, Identity, Connectors, and Elasticsearch or OpenSearch — realistically a Kubernetes deployment with the official Helm charts. The SaaS offering removes that burden entirely. Zeebe itself is impressively scalable (partitioned, replicated, benchmarked at very high throughput), but you are operating (or renting) a platform that is separate from your applications.

**Temporal** is also a dedicated cluster: the Temporal server (frontend, history, matching, worker services) plus a persistence store (Cassandra, PostgreSQL, or MySQL) and optionally Elasticsearch for search/visibility. Your application code runs in *workers* that long-poll the cluster over gRPC. Temporal Cloud is the managed alternative. The local dev server is a single binary, so getting started is easy — the operational weight comes later, in production.

**EventConductor** inverts the model: the engine is a **library inside your Spring Boot application**. There is no separate orchestrator platform to operate. Three modes, selected by configuration only, cover the whole spectrum with zero changes to business code:

| Mode | Infrastructure | Typical use |
|---|---|---|
| `embedded` + `memory` | None | Unit tests, demos, prototyping |
| `embedded` + `jpa` | PostgreSQL / MariaDB / Oracle / H2 | Single-node production, local dev |
| `kafka` + `jpa` | PostgreSQL + Kafka | Multi-pod distributed production |

In distributed mode, multiple orchestrator instances coordinate through PostgreSQL advisory locks and the transactional outbox pattern, and stateless workers scale horizontally as Kafka consumers. The honest trade-off: EventConductor has not been benchmarked at the extreme throughputs Zeebe and Temporal publish. If you need millions of process instances per day, the dedicated-cluster architectures have proven headroom; for the vast majority of business workloads (thousands to hundreds of thousands of instances per day), an embedded engine on PostgreSQL + Kafka is simpler to operate and more than sufficient.

## Robustness & correctness guarantees

For a production evaluation, the delivery and consistency semantics matter more than any feature list. This is what each engine actually guarantees:

**Camunda 8 (Zeebe)** persists workflow state in an internal, partitioned, **Raft-replicated log** — a broker crash is survived by replica failover with no data loss up to the committed log. Job execution by workers is **at-least-once** (a worker may receive a job again if it fails to complete it in time), so worker code should be idempotent. The engine's internal state transitions are effectively exactly-once within a partition. This architecture is battle-tested: it is the same design that Camunda benchmarks at thousands of process instances per second.

**Temporal** takes durability further than any other engine: the **full event history** of every workflow is persisted, and after any crash the workflow *function* is replayed deterministically against that history — workflow state is effectively **exactly-once**, even across process, pod, and datacenter failures. Activities are **at-least-once** (retried per policy), so activity code should be idempotent. This "durable execution" model is Temporal's core innovation and its strongest robustness argument.

**EventConductor** builds its guarantees on two well-understood primitives — the **transactional outbox pattern** and **database advisory locks** — rather than a custom replicated log:

- Every state transition is an immutable domain event written **in the same database transaction** as the state change, then relayed (publish-then-mark, so delivery is **at-least-once**; a crashed relay re-delivers, never loses).
- **Idempotency guards** absorb the resulting duplicates: duplicate `ProcessCreationRequested` events with the same business key create exactly one process; a duplicate task dispatch for a step already past `PENDING` is ignored; worker reports arriving for steps in a terminal state (e.g. after cancellation) are discarded.
- In multi-pod deployments, orchestrator instances coordinate through **PostgreSQL advisory locks**, so each step is dispatched exactly once even when several pods consume the same events.
- A **timeout scheduler** detects hung tasks (workers that never respond) and drives them through the same retry/error/compensation pipeline; condition expressions are **fail-closed** (an evaluation error means the guarded step does not run) and evaluated in a **sandboxed JEXL** environment that blocks reflection and system access.

These guarantees are not just claimed — they are pinned down as an explicit, public test specification ([TESTING.md](https://github.com/miguelperezcolom/eventconductor/blob/main/TESTING.md)) covering orchestration semantics, failure handling, idempotency, durability through the real outbox, and security, run in CI on every commit. A distributed chaos suite (orchestrator crash recovery, two-pod dispatch exclusivity, worker crash redelivery) is specified in the same document.

The honest framing: Temporal's replay model gives the strongest workflow-state durability of the three; Zeebe's replicated log is proven at very high scale. EventConductor deliberately chooses **boring, auditable technology** — ACID transactions, an outbox table you can inspect with SQL, and database locks — which an operations team can reason about and debug without learning a new distributed system.

## Scalability & performance

**Camunda 8** scales horizontally by adding Zeebe partitions and brokers. Published numbers: the smallest SaaS cluster is rated at 17 process instances/second, Camunda's own benchmarks routinely run at 2,000 PI/s, a tuned financial-services scenario reached [6,000 PI/s with sub-second cycle time](https://camunda.com/blog/2025/02/state-of-zeebe-performance/), and [Intuit reported a sustained 400–500 PI/s](https://camunda.com/blog/2024/08/scaling-workflow-engines-intuit-camunda-8-zeebe/) in production. If raw orchestration throughput is the deciding criterion, Zeebe has the strongest published evidence.

**Temporal** is horizontally scalable until the persistence layer becomes the bottleneck; with Cassandra that ceiling is very high. Temporal states the platform supports [millions of concurrent workflow executions](https://docs.temporal.io/workflow-execution), and organizations publicly report workloads on the order of 200M workflows/month. Temporal measures capacity in *state transitions per second* rather than workflows per second, since workflow cost varies enormously — worth keeping in mind when comparing numbers.

**EventConductor** scales along the same two axes as any event-driven system:

- **Workers** are stateless Kafka consumers — add instances to a consumer group to scale task execution linearly. The engine dispatches work and drives the state machine; it never executes business logic itself, so it does not become a bottleneck as business logic grows heavier.
- **Orchestrator instances** scale horizontally behind advisory locks; state lives in PostgreSQL, so orchestration throughput ultimately follows your database's write capacity — a well-understood scaling model (connection pooling, partitioning, read replicas) rather than a new one.

EventConductor's published baseline comes from the distributed test suite (DIST-05 in [the test plan](https://github.com/miguelperezcolom/eventconductor/blob/main/TESTING.md), reproducible with `mvn -Pdist-e2e`). It creates **500 concurrent process instances** — three worker-executed steps each, i.e. 1,500 task executions — and asserts they all complete with **no lost or stuck instances**. The most recent run:

| DIST-05 metric | Measured |
|---|---|
| Process instances | 500 (3 ACTION steps each → 1,500 task executions) |
| Wall clock (submit → all completed) | **11.3 s** |
| End-to-end throughput | **44.1 process instances/second** |
| Engine-side window (first creation → last completion) | 8.4 s → **59.7 PI/s** |
| Lost or stuck instances | **0** |

The setup is deliberately modest: laptop-class hardware (Apple M3 Max), a single PostgreSQL and a single Kafka broker in Docker, two orchestrator instances and one worker JVM, default engine settings (200 ms outbox poll). That is a smoke baseline, not a tuned ceiling — but even it amounts to ~3.8M three-step instances/day, and it is measured with the full durability path engaged (every event through the transactional outbox, every dispatch behind advisory locks). Perspective matters here: a process instance per second is ~86,400 instances/day, and 100 PI/s — comfortably within reach of a single decent PostgreSQL instance — is ~8.6M instances/day. The published headroom of Zeebe and Temporal is genuinely necessary for hyperscale event processing; for typical business-process workloads (bookings, approvals, onboarding, back-office flows), all three engines are far from their limits, and the deciding factors are operational complexity and fit, not throughput.

## Licensing & cost

- **EventConductor** is **MIT-licensed**, free for any use, including production. Your only costs are your own infrastructure (a database you probably already run, plus Kafka if you go distributed).
- **Temporal**'s server is **MIT-licensed** and free to self-host; you pay in operational effort or by using **Temporal Cloud**.
- **Camunda 8** moved to a **source-available license** (Camunda License 1.0) with version 8.6: self-managed is free for development and non-production use, but **production self-managed requires a paid Enterprise license** (a free non-commercial license exists for qualifying individuals, academia, and non-profits). Camunda 7's community edition reached end of life in October 2025.

If a fully free, open-source production deployment matters to you, that narrows the field to Temporal (with the operational cost of a cluster) or EventConductor (embedded, on infrastructure you already have).

## Failure handling: retries, timeouts, sagas

All three handle failure well — the difference is *where* you express it.

**EventConductor** — declaratively, per step in the definition:

```yaml
- id: reserve-flight
  type: ACTION
  topic: flight-service
  timeout: PT30S
  retries: 2
  rollbackable: true
  compensationStepId: cancel-flight
```

On exhausted retries, compensation steps for previously completed `rollbackable` steps run automatically in reverse order — the saga pattern without writing saga code.

**Camunda** — via BPMN constructs: job retries on service tasks, timer boundary events for timeouts, error boundary events, and BPMN compensation events with compensation handlers. Complete and standard, but spread across diagram elements and their technical bindings.

**Temporal** — in code, with the finest-grained control of the three: per-activity retry policies (initial interval, backoff, max attempts, non-retryable error types), multiple timeout types (schedule-to-start, start-to-close, heartbeat), and sagas implemented as explicit compensation logic in the workflow function. Maximum flexibility; you write and test it yourself.

## Human tasks & forms

This is where the three diverge most sharply:

- **EventConductor** ships a **forms engine as a first-class module**: form definitions in JSON, a drag-and-drop form editor, validation, versioned storage, and dynamic rendering to any front-end. A `USER_TASK` step references a `formId`; the engine creates the form execution, pauses the process, and resumes it on submission. Task list and form UI are included.
- **Camunda** also treats human tasks as first-class: BPMN user tasks, Camunda Forms with a visual form builder, and the Tasklist application. Mature and complete — human workflow has been Camunda's bread and butter since its beginnings.
- **Temporal** has **no built-in human task concept**. A human step is modeled as a workflow waiting on a signal or update; the task inbox, form rendering, validation, and completion UI are all yours to build. Perfectly doable — but it is application work, not engine features.

If your processes are human-heavy (approvals, reviews, data entry), EventConductor and Camunda give you a large head start over Temporal.

## AI integration (MCP)

The landscape changed in 2025–2026 and all three now have an AI story — but they address different problems:

- **Camunda** focuses on *AI inside the process*: the AI Agent connector plus BPMN ad-hoc sub-processes let an LLM-driven agent choose and invoke tools as part of a running process, with an MCP client connector (and A2A support arriving in 8.9) for external tools, plus an MCP server over its orchestration API. Powerful, and tied to the BPMN toolchain and its licensing.
- **Temporal** focuses on *durable AI applications*: integrations with the OpenAI Agents SDK and Google ADK run LLM calls and tool executions as retryable Activities, and a Temporal MCP server lets AI assistants inspect and operate a cluster in natural language.
- **EventConductor** focuses on *operating the engine — and your domain — through AI, out of the box*: every module (orchestrator, forms engine, and any of your own services via the `McpTools` interface) exposes its capabilities as **native MCP servers**, and the bundled `ia-agent-service` connects them to an LLM so operators can say *"retry all failed processes from today"* or *"show me pending user tasks for the onboarding workflow"* with **zero integration code**. Each server self-describes its domain through a `system-context` MCP prompt, so the agent's knowledge stays current as your services evolve.

EventConductor was, to our knowledge, the first workflow engine to ship native MCP support; it remains the only one where MCP exposure of the engine *and your own business services* is a built-in, embeddable feature rather than a separate connector or add-on.

## Feature matrix

A detailed capability-by-capability view. ✅ built-in · 🟡 possible with extra work or limitations · ❌ not available.

| Capability | EventConductor | Camunda 8 | Temporal |
|---|---|---|---|
| Sequential & parallel execution (fork/join) | ✅ `FORK`/`JOIN`/`parallel` | ✅ gateways | ✅ native code |
| Sub-processes | ✅ `PROCESS` step | ✅ call activities | ✅ child workflows |
| Conditional branching | ✅ JEXL expressions | ✅ gateways + FEEL | ✅ native code |
| Dynamic / data-driven flow shape | 🟡 expressions over a static graph | 🟡 multi-instance, ad-hoc sub-processes | ✅ unrestricted (it's code) |
| Declarative retries & timeouts | ✅ per step | ✅ BPMN + job retries | 🟡 in code (finest control) |
| Saga compensation | ✅ declarative, reverse-order | ✅ BPMN compensation events | 🟡 coded manually |
| Human tasks + forms + task UI | ✅ built-in module + editors | ✅ Tasklist + Forms | ❌ build your own |
| Timer / delay steps ("wait 3 days") | ✅ `TIMER` step (duration or date variable) | ✅ timer events | ✅ durable timers |
| Cron / scheduled process starts | ✅ `cronExpression` on the definition | ✅ timer start events | ✅ Schedules |
| Message correlation into running processes | ✅ `MESSAGE` step (businessKey or JEXL correlation) | ✅ message events | ✅ signals & updates |
| Query running workflow state | ✅ repositories / API / UI | ✅ Operate API | ✅ queries + visibility API |
| Business decision tables | ❌ | ✅ DMN engine | 🟡 code |
| Definition versioning | ✅ versions + draft working copies | ✅ versioned deployments | 🟡 code patching APIs |
| Per-definition concurrency limits & queueing | ✅ `maxConcurrentExecutions` + enqueue | 🟡 not built-in | 🟡 workflow-id uniqueness, worker limits |
| Multi-tenancy | ❌ | ✅ | ✅ namespaces |
| GitOps import of definitions | ✅ built-in Git import | 🟡 Web Modeler / pipelines | n/a (code deploys) |
| Audit trail / history per instance | ✅ logs + step history | ✅ Operate + history data | ✅ full event history |
| Engine metrics (Prometheus etc.) | ✅ engine counters/timers/gauges via Micrometer ([reference](/reference/configuration/#metrics)) | ✅ extensive | ✅ extensive |
| Process analytics & reporting | ✅ built-in per-definition analytics ([guide](/guides/analytics/)) — not a BI suite like Optimize | ✅ Optimize | 🟡 visibility/search attributes |
| Workflow & form visual editors | ✅ included, output is JSON | ✅ Modeler (best-in-class) | ❌ |
| AI agent / MCP integration | ✅ native, engine + your services | ✅ connectors (8.8+) | ✅ SDK integrations + MCP server |
| Embeddable in your application | ✅ core design | ❌ | ❌ (dev server only for tests) |
| Zero-infrastructure test mode | ✅ in-memory, in-process | 🟡 Testcontainers | 🟡 local dev server / test framework |

The gaps are as informative as the checkmarks: if you need DMN decisions or multi-tenancy **today**, Camunda and Temporal have them and EventConductor does not (yet). Conversely, if you want the engine *inside* your application, workflows as reviewable data, forms included, and MIT licensing in production, only EventConductor offers that combination.

## When to choose which

**Choose Camunda if:**

- BPMN is a requirement — analysts model processes, diagrams are compliance artifacts, or you migrate from another BPMN engine.
- You want the most mature tooling for business/IT collaboration (Modeler, Optimize analytics) and enterprise support, and the licensing cost is acceptable.
- Process orchestration is a platform-level, organization-wide concern with a dedicated team to run it.

**Choose Temporal if:**

- Your workflows are complex *code* — dynamic branching, rich data structures, logic that a declarative format can't express comfortably.
- You need extreme scale or polyglot workflow authors (Go, TypeScript, Python, .NET teams writing their own workflows).
- Human tasks are rare or absent, and your team is comfortable with determinism rules and workflow versioning.

**Choose EventConductor if:**

- You live in the Java/Spring ecosystem and want orchestration as a **library, not another platform to operate**.
- You want workflows as reviewable, versionable *data* files — with retries, timeouts, sagas, parallelism, and human tasks declared, not coded.
- Human tasks and forms matter, and you want them (plus editors and a management UI) included.
- You want MIT-licensed software in production and the same code running from a unit test to a Kubernetes cluster.
- AI-driven operations via MCP is part of your vision, without building the integration yourself.

It is fair to also state the reverse: EventConductor is a young project without Camunda's ecosystem or Temporal's proven extreme-scale deployments, its engine is Java/Spring only (workers can be any language via Kafka), and there is no BPMN standard behind it. If those are your constraints, the alternatives above are excellent tools.
