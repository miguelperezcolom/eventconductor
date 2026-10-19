---
title: Asynchronous Request/Response
description: Answer another system's request with an event or a callback, or send a request and wait for its answer — with correlation, deadlines and duplicates handled, and without losing a reply that comes back fast.
---

Two systems talk in request/response style without holding a connection open: one sends a request, the other answers later, and the answer finds its way back by a **correlation id** carried in both. EventConductor (EC) can be on either side:

- **A. EC answers.** A request — a domain event, a message, a Kafka record — starts a process; the process does its work and replies with a `PUBLISH_EVENT` (or an `HTTP_CALL` callback) that carries the correlation id taken from the request.
- **B. EC asks and waits.** A process sends the request (`PUBLISH_EVENT` or `HTTP_CALL`) and waits for the answer — in the same step, with an `awaitReply` — with a deadline and a route for when no answer comes.

Pattern B has one trap — **a reply that arrives before the wait is armed is dropped** — and two definition shapes that avoid it: one step with an `awaitReply` (recommended), or a `FORK` of the request and a `WAIT_FOR_MESSAGE`. [Read that section](#b-ec-asks-and-waits) before you write the definition.

## Synchronous or asynchronous?

| | [Synchronous invocation](/guides/synchronous-invocation/) | Asynchronous request/response (this guide) |
|---|---|---|
| The caller | holds an HTTP request open until the process `REPLY`s or the deadline passes | sends and moves on; the answer comes back on its own channel |
| Answer time | seconds (bounded by the HTTP deadline) | anything — minutes, days, a human in the loop |
| Channel | HTTP to EC | events (Kafka topics / destinations), messages (`/workflow/api/messages`, the `upstream` topic), HTTP callbacks |
| Correlation | the HTTP connection itself | a correlation id in the request and the reply |
| Use it when | a UI or an API gateway needs the result in the same call | the other side is a system you exchange events with, the work is long, or the caller cannot block |

Both keep the process durable: the caller of a synchronous invocation is only *watching* the process. Choose by what the **caller** can do, not by what the process does.

## A. EC answers

The service below is started by a `com.acme.quote.requested` event, prices the item, and answers on `quote-replies` with the requester's `requestId` as record key and in the payload. If pricing takes too long it still answers — with `com.acme.quote.failed` — so the requester never waits for nothing.

```yaml
id: quote-service
name: Quote service (answers quote requests)
version: 1
triggers:
  - id: on-quote-requested
    destination: quote-requests
    type: com.acme.quote.requested
    businessKey: "quote-service-${data.requestId}"   # one process per request, however often it is delivered
    variables:
      requestId: "${data.requestId}"                 # the requester's correlation id
      item: "${data.item}"
steps:
  - id: start
    type: START
    name: Start
  - id: price
    type: ACTION
    name: Price the item
    topic: pricing
    preconditionStepId: start
    timeout: PT30S
    retries: 0
    onTimeoutStepId: cannot-price
  - id: reply
    type: PUBLISH_EVENT
    name: Quote ready
    preconditionStepId: price
    event:
      destination: quote-replies
      type: com.acme.quote.ready
      key: "${requestId}"                            # the correlation id goes back as the record key…
      payload:
        requestId: "${requestId}"                    # …and in the payload
        price: "${price}"
  - id: cannot-price
    type: PUBLISH_EVENT
    name: Quote failed
    preconditions:
      - stepId: price
        expression: "false"                          # reached only through price's onTimeoutStepId
    event:
      destination: quote-replies
      type: com.acme.quote.failed
      key: "${requestId}"
      payload:
        requestId: "${requestId}"
        reason: "pricing timed out"
  - id: answered
    type: JOIN
    name: Answered
    joinType: XOR
    preconditionStepIds: [reply, cannot-price]
  - id: end
    type: END
    name: End
    preconditionStepId: answered
```

What each part does:

- **The trigger takes the correlation id from the request** (`requestId: "${data.requestId}"`) and keeps it as a process variable for the reply. It is an [event trigger](/guides/domain-event-triggers/#event-triggers); in Kafka mode the engine consumes `workflow.events.destinations.quote-requests.topic` (with the `inbound-events-kafka` module on the classpath, as in `orchestrator-standalone-app`), in embedded mode the event comes from `InboundEventService.receive`, the REST inject `POST /workflow/api/events/quote-requests`, or — when the requester is a workflow of the same engine — the loopback of its `PUBLISH_EVENT`.
- **`businessKey: "quote-service-${data.requestId}"`** makes a redelivered request — or the same request sent twice with different event ids — land on the process that is already answering it: business keys are unique, and a creation with a key that exists is ignored. Put the workflow in the key (see [each event is used once](/guides/domain-event-triggers/#each-event-is-used-once)).
- **The reply is a [`PUBLISH_EVENT`](/guides/publishing-events/)**, written to the outbox in the transaction that completes it. Its CloudEvents `id` is the step execution id, stable across relay retries, so the requester can deduplicate on it.
- **The record key is the correlation id.** A requester that waits with `onEvent` and no `correlationKey` correlates on the record key by default; one that reads the payload uses `${data.requestId}`.

Other ways in and out:

- **Started by a message or a Kafka record.** Instead of a trigger, the requester can create the process directly: a `ProcessCreationRequested` on the `upstream` topic, or `ProcessUpstreamEventUseCase` in embedded mode ([Starting a process](/guides/starting-a-process/)). Put the correlation id in the variables and use it as the business key.
- **Answering with a callback.** Replace the `PUBLISH_EVENT` with an `HTTP_CALL` to the requester (a named `connection`, so the target host is configuration rather than data from the request):

  ```yaml
  - id: reply
    type: HTTP_CALL
    name: Call the requester back
    preconditionStepId: price
    retries: 3
    http:
      connection: requester
      method: POST
      path: "/quotes/${requestId}/result"
      body:
        requestId: "${requestId}"
        price: "${price}"
  ```

  Every retry sends the same `Idempotency-Key` (the step execution id), so a requester that honours it applies a repeated callback once ([HTTP calls](/guides/http-calls/)).
- **Answering a requester that is an EC workflow on another engine:** reply with a message, through that engine's `POST /workflow/api/messages` (an `HTTP_CALL`, with its API key — see [Calling the REST API](#calling-the-rest-api)) or a record on its `upstream` topic.

There is no race on this side: the process exists before it answers. The trap is on the requester's side — if the requester is an EC workflow, it is pattern B.

## B. EC asks and waits

### One step: the request and its reply

A message that matches no `PENDING` waiting step is **dropped, not buffered** ([step types](/reference/step-types/#wait_for_message)), so the wait must be armed by the time the reply can arrive. Give the request step an `awaitReply` and it is: the step that sends the request is also the one that waits for the answer, and its wait is saved in the **same transaction** that sends the request — the event's outbox row, the HTTP task's dispatch. The request cannot leave before the step is waiting for its reply.

```yaml
id: booking-quote
name: Book with a quote (asks, then waits for the answer)
version: 1
steps:
  - id: start
    type: START
    name: Start
  - id: ask-quote
    type: PUBLISH_EVENT
    name: Ask for a quote and wait for it
    preconditionStepId: start
    timeout: PT5M                                    # the whole exchange: request and reply
    retries: 0
    onTimeoutStepId: no-quote
    event:
      destination: quote-requests
      type: com.acme.quote.requested
      key: "${process.businessKey}"
      payload:
        requestId: "${process.businessKey}"          # the correlation id the answer must carry
        item: "${item}"
    awaitReply:
      messageName: quote-ready
      correlationExpression: businessKey             # known before the request leaves
      onEvent:
        destination: quote-replies
        type: com.acme.quote.ready
        correlationKey: "${data.requestId}"
        variables:
          price: "${data.price}"
  - id: book
    type: ACTION
    name: Book at the quoted price
    topic: booking
    preconditionStepId: ask-quote
  - id: no-quote
    type: ACTION
    name: Tell the customer there is no quote
    topic: booking
    preconditions:
      - stepId: ask-quote
        expression: "false"                          # reached only through ask-quote's onTimeoutStepId
  - id: outcome
    type: JOIN
    name: Outcome
    joinType: XOR
    preconditionStepIds: [book, no-quote]
  - id: end
    type: END
    name: End
    preconditionStepId: outcome
```

- **`awaitReply`** has the fields of a `WAIT_FOR_MESSAGE` — `messageName`, `correlationExpression` (both required) and an optional `onEvent` — and means the same: the step waits for `quote-ready` with this process's business key. `ask-quote` publishes, stays `PENDING`, and completes when the reply is correlated; `book` runs after it with `price` in the process.
- **`onEvent`** turns a `com.acme.quote.ready` event on `quote-replies` whose `data.requestId` is this process's business key into the reply, and maps `data.price` into the process. Without it, the reply is a plain message — `messageName: quote-ready`, `correlationKey: <business key>` — through `POST /workflow/api/messages`, the `upstream` topic, a `SEND_MESSAGE` or the `sendMessage` MCP tool.
- **The correlation id is chosen before the request leaves** — here the business key. That is what lets the wait be armed with the request: it must already know the key the reply will carry. Do not correlate on an id the other system returns (an `HTTP_CALL` output, say); if you need one, have the other side echo yours.
- **`timeout: PT5M` + `onTimeoutStepId: no-quote`** routes to `no-quote` when no answer arrives; `no-quote` is guarded by `expression: "false"` on `ask-quote`, so it runs *only* through the timeout route. The step's `timeout` (and `deadline`) bound the request and the reply together.
- **`retries`** send the request again and wait again. A retried `PUBLISH_EVENT` is a new event — its CloudEvents `id` is `<step execution id>-<attempt>` — so a responder that deduplicates on the id answers the re-ask.

The quote service of [pattern A](#a-ec-answers) is the other half of this exchange: both definitions are in the engine's end-to-end tests, run against each other in one engine.

### The same with an HTTP request

When the request is an HTTP call and the answer comes back later (a webhook, a callback, an event), put the `awaitReply` on the `HTTP_CALL`:

```yaml
- id: ask-quote
  type: HTTP_CALL
  name: Ask for a quote and wait for it
  preconditionStepId: start
  timeout: PT5M
  retries: 2
  onTimeoutStepId: no-quote
  http:
    connection: pricing
    method: POST
    path: /quotes
    body:
      requestId: "${process.businessKey}"
      item: "${item}"
      replyTo: quote-ready
    output:
      ticket: "body.ticket"
  awaitReply:
    messageName: quote-ready
    correlationExpression: businessKey
```

The wait is armed before the HTTP task is dispatched to its worker, so a callback the remote system sends *before* it answers the HTTP request — which a fast system does — is caught. The order of the HTTP response and the reply does not matter:

| What comes first | What happens |
|---|---|
| The 2xx response | It is recorded — `output` mapped into the process — and the step keeps waiting, `PENDING`, under the same `timeout`. The reply completes it. |
| The reply | It completes the step. The response that comes after finds the step complete and is ignored, `output` included — so successors must not rely on `output`; carry what they need in the reply. |
| A failure (non-2xx, I/O error) | The step fails as any `HTTP_CALL` does: `retries` send the request again (same `Idempotency-Key`) and re-arm the wait; once they are spent, the process fails. A reply that arrives while the step waits out its backoff matches nothing. |

A retried `HTTP_CALL` may produce more than one answer; the first completes the step and the others match nothing.

### The alternative: a FORK

Before `awaitReply` (2.28.0) the safe shape was two steps behind a `FORK`, and it is still correct — use it when the request and the wait need different settings (a request with its own retries, a wait with its own deadline), or when something else should happen between sending and waiting. The engine starts every step whose preconditions are met in the **same pass**, so the wait becomes `PENDING` in the same transaction that writes the request to the outbox:

```yaml
  - id: fork
    type: FORK
    name: Arm the wait and send the request together
    preconditionStepId: start
  - id: wait-quote
    type: WAIT_FOR_MESSAGE
    name: Wait for the quote
    preconditionStepId: fork
    messageName: quote-ready
    correlationExpression: businessKey
    timeout: PT5M
    retries: 0
    onTimeoutStepId: no-quote
  - id: ask-quote
    type: PUBLISH_EVENT                              # or HTTP_CALL
    name: Ask for a quote
    preconditionStepId: fork
    event:
      destination: quote-requests
      type: com.acme.quote.requested
      key: "${process.businessKey}"
      payload:
        requestId: "${process.businessKey}"
        item: "${item}"
  - id: quoted
    type: JOIN
    name: Request sent and quote in
    preconditionStepIds: [ask-quote, wait-quote]
  - id: book
    type: ACTION
    name: Book at the quoted price
    topic: booking
    preconditionStepId: quoted
```

The order of the two branches in the file does not matter; the `JOIN` `quoted` continues once the request is out *and* the answer is in. With an `HTTP_CALL` in place of `ask-quote`, the wait completes on the callback whatever the HTTP response says — failing the request does not stop the wait, unlike with `awaitReply`.

### Never: request, then wait

<!-- ec-example: skip -->
```yaml
# Unsafe: a fast reply can arrive before wait-quote is PENDING, and is lost.
- id: ask-quote
  type: PUBLISH_EVENT        # or HTTP_CALL
  # …
- id: wait-quote
  type: WAIT_FOR_MESSAGE
  preconditionStepId: ask-quote
  # …
```

`wait-quote` starts only once `ask-quote` has completed — in a *later* unit of work than the one that sent the request. Every engine mode can lose the reply.

### Why the sequential shape loses replies

What the code does (references to `modules/workflow-engine/src/main/java/io/mateu/workflow/`):

- A `PUBLISH_EVENT` completes inside a step-over: `StepOverProcessUseCase.resolvePublishSteps` queues the `ExternalEventRequested` on the step and then completes it (`stepExecution.publish(event)`, then `updateStatus(COMPLETED)`, which queues a `StepExecutionStatusChanged`). Its successor is *not* started in that pass — `WorkflowOrchestrationService.calculateNextTransitions` already ran — but by the step-over that the `StepExecutionStatusChanged` event triggers.
- **Memory persistence**: `InMemoryStepExecutionRepository.save` dispatches the step's events synchronously, in order. The `ExternalEventRequested` reaches the application's `ExternalEventPublisher` (`ExternalEventRequestedHandler`) *before* the status change that starts the wait. A reply sent from there, or from any thread before that, is dropped.
- **Embedded + JPA**: both events are outbox rows of one transaction. `EmbeddedOutboxRelay` handles them one after another, the request first; the wait is armed only when the status-change row is handled. A reply that arrives in between is dropped.
- **Kafka mode**: `OutboxRelay` sends the request to the destination's topic and the status change to the engine's `outbox` topic in the same pass; the wait is armed only when an orchestrator consumes that record and steps the process over. A responder that is faster than that — or an orchestrator that lags, rebalances or retries the step-over — loses the reply.
- **`HTTP_CALL`**, every mode: the wait starts after the worker reports the response and `UpdateStepExecutionUseCase` steps the process over. A callback sent before that is dropped.

Why `awaitReply` (and the fork) are safe:

- **`awaitReply`**: `StepExecution.start` materialises the step's awaited message name and correlation key — and announces its routing subscription — on the step itself, *before* the request is rendered or dispatched; the request (`ExternalEventRequested`, `TaskExecutionRequested`) is queued on the same step and saved with it.
- **The fork**: `calculateNextTransitions` starts every `CREATED` step whose preconditions hold in one pass, so the wait and the request start together, and the wait's `PENDING` status, message name and correlation key are saved in the same transaction as the request's outbox row (or its `TaskExecutionRequested`).
- **JPA (embedded and Kafka)**: relays only read committed rows, and `InlineDrive` never claims an `ExternalEventRequested`, so the request cannot leave before the wait is committed `PENDING`.
- **Memory**: the repository stores the step before it dispatches the step's events, so a reply sent while the request is being delivered finds the step waiting.
- **One caveat, sharded engines**: with `workflow.sharding.message-routing.filter.enabled=true` (off by default) a shard drops a broadcast message its Bloom filter has not seen yet, and the filter learns about a new wait asynchronously (`MessageSubscriptionProjectionHandler`) until its periodic rebuild. A reply broadcast in that window is dropped — with either shape.

The end-to-end tests `AwaitReplyE2eTest` (memory) and `AwaitReplyJpaE2eTest` (JPA) prove the single step with the fastest possible responder — one that answers on the thread that delivers the request — for `PUBLISH_EVENT`, `HTTP_CALL` and an `onEvent` reply, plus the timeout route, retries and the response/reply order. `RequestResponseE2eTest` and `RequestResponseJpaE2eTest` show the sequential shape losing the reply and the forked one catching it; `RequestResponseGuideE2eTest` runs the two definitions of this guide against each other.

## Correlation

| Where | Field | What it is |
|---|---|---|
| The waiting step | `correlationExpression` | A JEXL expression over the process that yields the key a message must carry. `businessKey` correlates by business key; `requestId` by a variable. Required. An expression that cannot be evaluated matches nothing. |
| A message | `correlationKey` | The key it carries (`POST /workflow/api/messages`, the `upstream` topic, `SEND_MESSAGE`). Matches when equal to the step's key. |
| An `onEvent` wait | `onEvent.correlationKey` | A template over the event that yields the message's key. Absent: the event's record key, else its `subject`. |
| A trigger | `businessKey` | A template over the request for the started process's business key — the natural place for the requester's correlation id. |

The step's key is computed when the wait starts and recomputed when the variables it reads change. Every process waiting on the same message name and key is woken by one message. A message's variables are merged into the process.

## Timeouts and deadlines

- **`timeout`** (`PT5M`, or milliseconds) counts from the moment the wait starts — with `awaitReply` or the fork, the moment the request is sent.
- **`deadline`** — a moment from the process's data (`{ date: "${checkinAt}", offset: "-P1D" }`) — when the answer is due by a date rather than within a duration. With both, the earlier wins. See [Scheduling and deadlines](/guides/scheduling-and-deadlines/#finishing-by-a-moment-deadline).
- **`onTimeoutStepId`** routes to a step of your choice instead of failing the process: tell the customer, try another supplier, cancel. Without it a timed-out wait ends `TIMEOUT` and the process errors once retries are spent ([On-timeout routing](/guides/retries-timeouts-compensation/#on-timeout-routing)).
- **`retries` on the wait** re-arms it for another `timeout` — and with `awaitReply`, sends the request again first. A deadline is not extended by retries. Keep `retries: 0` unless asking (or waiting) again is what you mean.
- On the answering side, give the slow step a timeout and an answer for it (`cannot-price` above), so the requester gets a negative answer instead of its own timeout.

## Duplicates and idempotency

- **A reply that comes after the wait has completed** (a redelivery, a second answer, a retried `HTTP_CALL`'s answer) matches no waiting step and is ignored. Since a wait that completed no longer matches, the step completes once.
- **A redelivered domain event is used once per receiver.** A trigger never starts two processes for one event (same CloudEvents `source` and `id`), and an `onEvent` wait never takes the same event twice. Since 2.27.0 the receipt of an `onEvent` wait is recorded only when a waiting step actually takes the event: one that arrived before its step was waiting leaves no receipt, so a redelivery of it is tried again rather than refused as a duplicate.
- **Requests repeated with different ids** are absorbed by a business key derived from the correlation id (`quote-service-${data.requestId}`).
- **Replies EC sends** are at-least-once: `PUBLISH_EVENT` carries a stable CloudEvents `id`, `HTTP_CALL` a stable `Idempotency-Key`. Consumers deduplicate on them.

## Calling the REST API

A responder that answers with a message or an event over HTTP — `POST /workflow/api/messages`, `POST /workflow/api/events/{destination}` — must authenticate. Since 2.27.0 the engine's REST API (`/workflow/api/**`) refuses anonymous requests: it lets a request in when its `X-Api-Key` matches `workflow.message-api.api-key`, or no key is set and a Spring Security filter chain protects the application (send that application's credentials), or `workflow.message-api.allow-anonymous=true` (isolated deployments only); otherwise `401`. See [HTTP security](/reference/configuration/#http-security).

```http
POST /workflow/api/messages
X-Api-Key: <workflow.message-api.api-key>
Content-Type: application/json

{"messageName": "quote-ready", "correlationKey": "booking-quote-B-1", "variables": {"price": "129.00"}}
```

The answer is `202` whether or not a step was waiting: the API cannot confirm a match, which is one more reason to arm the wait before the request leaves — with `awaitReply`.
