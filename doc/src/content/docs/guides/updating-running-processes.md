---
title: Updating Running Processes
description: Declare named updates on a definition — validated writes to a live process that answer with a result — and call them over HTTP, MCP, Kafka or the test kit; update-with-start included.
---

An **update** is a write to a process that is running, which the process **validates** and **answers**. A customer changes the shipping address of an order that is waiting to ship; the order checks the new address, records it, and replies with what it will now ship to — or says no, and nothing changes.

It is EventConductor's counterpart of Temporal's *Workflow Updates* (and *update-with-start*), declared in the definition rather than coded in a handler:

- **Validated.** The payload against a JSON Schema, then guards over the payload and the process's variables. A refusal leaves no trace in the process.
- **Atomic and ordered.** Applied by the node that owns the process, under the process lock, in one transaction: one update at a time per process, in the order they reach it.
- **Answered.** The reply is computed from the process *after* the update — and recorded, so a retry with the same `Idempotency-Key` gets the same answer instead of a second update.
- **Any number of times.** Unlike a [`REPLY`](/guides/synchronous-invocation/) (one per process), a process takes as many updates as its life allows.

## Updates, messages, synchronous invocation, operations

| | Message (`WAIT_FOR_MESSAGE`) | [Synchronous invocation](/guides/synchronous-invocation/) | [Instance operations](/guides/operating-running-processes/) | **Update** |
|---|---|---|---|---|
| Who sends it | another system | a caller starting a process | an operator | a caller of a live process |
| Declared in the definition | yes | yes (`syncInvocation`, `REPLY`) | no — admin commands | yes (`updates`) |
| Validation | correlation only | definition + input limits | operation rules + a reason | JSON Schema + guards |
| Answer | `202`, fire-and-forget; dropped if nobody waits | the process's reply, once | `202` | `200` + result, every time |
| Changes | the variables it carries; resumes the waiting step | starts a process | anything, audited | what `set` says; resumes a step if `completes` says so |

Use a **message** when another system reports something happened and does not need an answer; an **update** when the caller is asking the process to change and needs to know whether it did, and to what.

## Declare the updates

```yaml
id: order-fulfilment
steps:
  - { id: start, type: START, name: Start }
  - { id: reserve, type: ACTION, name: Reserve stock, preconditionStepId: start,
      compensable: true, compensationStepId: release }
  - { id: awaitShipping, type: WAIT_FOR_MESSAGE, name: Await shipping, messageName: ship-order,
      correlationExpression: businessKey, preconditionStepId: reserve }
  - { id: ship, type: ACTION, name: Ship, preconditionStepId: awaitShipping }
  - { id: release, type: ACTION, name: Release stock }
  - { id: end, type: END, name: End, preconditionStepId: ship }
updates:
  - name: changeAddress
    description: Change the shipping address while the order waits to ship.
    accepts:                                     # JSON Schema (2020-12) of the payload → 400
      type: object
      required: [address]
      properties: { address: { type: string, minLength: 5 } }
    acceptedWhile: [awaitShipping]               # only while this step waits → otherwise 409
    validate:                                    # guards, in order → the first false is a 422
      - expression: "payload.address != address"
        message: That is already the shipping address.
    set:                                         # what it writes: variable → JEXL
      address: payload.address
      addressChanges: "addressChanges == null ? 1 : addressChanges * 1 + 1"
    reply: "{'address': address, 'changes': addressChanges}"
  - name: approveShipping
    completes: awaitShipping                     # resumes the waiting step: the flow moves on
    set: { approvedBy: payload.by }
    reply: approvedBy
  - name: addNote
    accepts: { type: object, required: [note] }
    set: { notes: "notes == null ? payload.note : notes + '\n' + payload.note" }
    sensitive: [customerPin]                     # never shown in the recorded payload
```

| Field | Meaning |
|---|---|
| `name` | Unique in the definition; a word (`[A-Za-z][A-Za-z0-9_.-]*`). The `{name}` of the endpoint |
| `description` | Shown in the console and the MCP tools |
| `accepts` | JSON Schema (2020-12) of the payload. Not satisfied (or not JSON): `400`, and nothing is recorded |
| `acceptedWhile` | Step ids: the update is accepted only while one of them is in progress (`PENDING`, `RUNNING`, waiting to retry). Absent: whenever the process is `RUNNING` or `PENDING` |
| `validate` | Guards — `{expression, message}` — over `payload`, the variables and `businessKey`. The first that is not `true` refuses the update with its message (`422`); an expression that cannot be evaluated refuses too (fail closed) |
| `set` | Variables written: name → JEXL over `payload`, the variables **as they were before the update**, and `businessKey`. A value that is not text is stored as its JSON |
| `completes` | A `WAIT_FOR_MESSAGE` step the update resumes, exactly as its message would; it must be waiting, or the update is not accepted (`409`). The same step can still be resumed by its message |
| `reply` / `replyTemplate` | The result: a JEXL expression, or a [payload template](/guides/payload-templates/) (`payload` in scope), evaluated **after** `set` is written. Neither: `{}`. At most `workflow.sync.max-reply-bytes` |
| `sensitive` | Payload fields that are never shown: the record carries them sealed (see [Sensitive variables](#sensitive-variables)) and every door shows `••••` |
| `allowSensitiveVariables` | Let `validate` and `reply`/`replyTemplate` read the definition's [sensitive variables](/guides/sensitive-data-and-encryption/). Off by default — see below |

Everything is checked when the definition loads: names, schemas, the steps named, and every expression's syntax. `updates` is part of the definition's [version](/reference/versioning/): a process accepts the updates of the version it runs (and of the one a [migration](/guides/migrating-running-processes/) moves it to).

### What happens, in order

Under the process lock, on the node that owns the process:

1. The process must be `RUNNING` or `PENDING`, and the `acceptedWhile` / `completes` step waiting → otherwise **not accepted** (`409`).
2. Each `validate` guard must be `true` → otherwise **refused** (`422`).
3. `set` is evaluated — all of it — against the process as it is.
4. `reply` is evaluated against the process with `set` applied.
5. Only now is anything written: the variables, the resumed step, and the update's record with its result — in one transaction. A failure in 3 or 4 is **failed** (`500`), and nothing is written.

Then what follows a change of variables follows it, as when a worker writes them: message correlation keys and timers re-arm, and a step held by a [link guard](/guides/workflow-definitions/) that now holds is released. A resumed step moves the flow on through its own completion.

A **paused**, **failed** (including while a saga rolls back) or **finished** process takes no updates. A process that a compensation is undoing runs the compensation steps with the variables the updates left — an update is part of the process's state, like any other write.

## Sensitive variables

In a definition that declares [`sensitiveVariables`](/guides/sensitive-data-and-encryption/), an update follows the same rules as every other write:

- **What `set` writes into a sensitive variable is sealed** like any variable — at rest in the process row and in transit. The payload fields that feed it (`cardNumber: payload.card` protects `card`), the fields named like a sensitive variable and the ones the update declares `sensitive` are **sealed in the update's record** (`process_update.payload`) and in the `process-update-requested` command; the node that applies the update opens them. Without encryption keys they are masked in the record instead.
- **Guards and replies answer the caller**, so they are an oracle on what they read: a definition whose update's `validate` or `reply`/`replyTemplate` reads a sensitive variable is **refused at load** unless the update opts in with `allowSensitiveVariables: true` (as an `LLM_CALL`'s `llm.allowSensitiveVariables`). At runtime the sensitive variables are left out of those expressions' context for an update that has not opted in; `set` always sees them (what it computes stays inside the engine).
- **A result allowed to read them** is recorded sealed and given in clear **only to the request that sent the update** — and to a retry with its `Idempotency-Key`, which is the same request. `GET /workflow/api/updates/{id}`, the process's update list, the MCP tools and the console's Updates tab show it masked (`••••`), as they show sealed payload fields and the result's members named like a sensitive variable. Reading the value itself is the variable's [audited reveal](/guides/sensitive-data-and-encryption/).
- **The idempotency fingerprint is keyed** (`h1:<keyId>:…`, an HMAC under a key derived from the active one), never a plain digest a card number could be guessed back from; a retry is verified under the key the stored fingerprint names.

```yaml
sensitiveVariables: [cardNumber]
updates:
  - name: replaceCard
    accepts: { type: object, required: [card] }
    set: { cardNumber: payload.card, last4: "payload.card.substring(12)" }
    reply: "{'last4': last4}"                 # fine: last4 is not sensitive
  - name: confirmCard
    reply: "{'card': cardNumber}"             # reads cardNumber: refused at load without
    allowSensitiveVariables: true             # this opt-in
```

## Call an update over HTTP

```bash
curl -X POST https://engine.example.com/workflow/api/processes/5f0c…/updates/changeAddress \
  -H 'X-Api-Key: …' -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: addr-7781' -H 'Prefer: wait=5' \
  -d '{"address": "Elm Street 2"}'
```

```http
HTTP/1.1 200 OK
Location: /workflow/api/updates/9d1e…

{"updateId": "9d1e…", "processId": "5f0c…", "name": "changeAddress", "status": "COMPLETED",
 "result": {"address": "Elm Street 2", "changes": 1},
 "payload": {"address": "Elm Street 2"}, "requestedAt": "…", "completedAt": "…",
 "location": "/workflow/api/updates/9d1e…"}
```

By business key: `POST /workflow/api/processes/by-key/updates/changeAddress?businessKey=ORDER-1001`.

| Answer | When |
|---|---|
| `200` | Applied; `result` is the reply, verbatim JSON |
| `202` + `Location` | The wait ran out first (`Prefer: wait=N`, capped by `workflow.sync.max-deadline-ms`; default `workflow.sync.default-deadline-ms`). The update is recorded and **still completes**; read it at `Location` (`GET /workflow/api/updates/{id}`, `Prefer: wait=N` long-polls) |
| `400` `INVALID_PAYLOAD` | Not JSON, not what `accepts` allows, or over the input limits — nothing recorded |
| `404` | No such process, or its definition version declares no such update |
| `409` `NOT_ACCEPTING` | The process is not accepting it now (the detail says why) |
| `422` `REFUSED` | A guard said no — its `message` is the `detail` |
| `422` `IDEMPOTENCY_KEY_REUSED` | The key was already used for a different update or payload |
| `500` `FAILED` | A `set` or `reply` expression failed: a fault of the definition. Nothing changed |
| `429` | Too many callers waiting on this node (`workflow.sync.max-waiting`) |
| `503` | The command could not be sent to the process's owner (Kafka); retry with the same key |

Refusals are `application/problem+json` with `reason`, and — when the refusal was recorded — `updateId`.

**Idempotency.** With an `Idempotency-Key`, a retry of the same update (same name, same payload as JSON — member order and whitespace do not matter) gets the recorded update: its result, or its refusal; it is never applied twice. The key is kept for `workflow.sync.retention` (24 h); the record itself stays with the process. Without a key, every request is a new update.

`GET /workflow/api/processes/{id}/updates` lists a process's updates, oldest first.

## Update-with-start

```bash
curl -X POST https://engine.example.com/workflow/api/definitions/order-fulfilment/update-with-start \
  -H 'X-Api-Key: …' -H 'Content-Type: application/json' -H 'Idempotency-Key: cart-42-add' \
  -d '{"businessKey": "CART-42", "variables": {"customer": "C-9"},
       "update": "addItem", "payload": {"sku": "A-1", "qty": 2}}'
```

- **No process has the key:** one is started, and the update is its first act — evaluated against the initial `variables`, its `set` seeds them, and the process and its completed update are written together, before the process runs its first step. `acceptedWhile` and `completes` are not checked on this branch (nothing is waiting yet). A refused update starts nothing.
- **A process of the definition has the key:** the update goes to it, exactly as `POST …/updates/{name}` (it may be refused, or not accepted if that process finished — keys are not reused).
- **A process of another definition has it:** `409` `BUSINESS_KEY_TAKEN`.

The answer is the update's, with `"started": true` when this call started the process. Two concurrent update-with-starts for one key start one process; the other's update goes to it.

Where the update goes is decided by an `UpdateWithStartPolicy` bean (`EXISTING` / `START` / `CONFLICT` for a definition and a key). The default implements the rule above; declare your own to change how keys of finished processes are treated.

## From MCP, Kafka, Java and tests

- **MCP:** `updateProcess(processId | businessKey, updateName, payloadJson, idempotencyKey?)` answers with the result or why not; `updateWithStart(...)`; `listProcessUpdates(processId)`.
- **Kafka:** `process-update-requested` on the `upstream` topic, keyed by the process id — `{"type": "process-update-requested", "updateId": "…", "processId": "…", "updateName": "addNote", "payload": "{\"note\":\"x\"}", "requestedBy": "billing"}`. The owner records and applies it (the `updateId` is its idempotency key); read the outcome from `GET /workflow/api/updates/{updateId}`. See [Kafka topics](/reference/kafka-topics/#upstream).
- **Java:** `ProcessUpdateService.submit(...)` and `await(...)`, the service behind the REST door.
- **Tests:** the [test kit](/guides/testing-workflows/#updates) — `runtime.update(process, "changeAddress", Map.of("address", "Elm 2"))` returns the outcome, `assertThat(outcome).isCompleted().hasResult(...)`.

## In the console

The process detail has an **Updates** tab: when, which update, who asked, how it ended, the payload as recorded (sensitive fields masked) and the result — or why it was refused. The process log has a line per update, naming the variables it wrote (never their values).

## How it runs

The door (the REST controller, an MCP tool) checks what it can without the process lock — the update exists in the version the process runs, the payload, the idempotency key, a plainly finished process — records the update as `REQUESTED` and sends `process-update-requested` to the node that owns the process. In embedded mode that is the same node, which applies it off the request thread; in kafka mode, the pod consuming the process's partition, so concurrent updates from several pods are serialized by partition ownership. The caller is woken when the outcome commits — at once on the same node, through a poll (`workflow.sync.poll-interval-ms`) across nodes — or answered `202` at its deadline.

A command lost on the way (a node that died before sending it) leaves the update `REQUESTED`; a retry with the same key sends it again, and the owner applies an update once whatever it receives.

The metric `eventconductor.process.updates` counts outcomes by definition, update and status.

## Limits

- The reply is computed when the update is applied. An update that must wait for worker steps before it can answer is a [synchronous invocation](/guides/synchronous-invocation/) or a [request/response](/guides/request-response/) today.
- In a sharded fleet, an update reaches the process from a node of its own shard.
