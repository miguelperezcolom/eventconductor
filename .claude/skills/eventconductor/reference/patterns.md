# Patterns — copy, paste, adapt

Every example is a complete, valid definition (checked by `AiReferenceExamplesTest`). Put each in
`src/main/resources/workflows/<id>.workflow.yaml`, run `mvn eventconductor:validate`, then test it
([testing.md](testing.md)). Field reference: [workflow-definitions.md](workflow-definitions.md).

## 1. Saga with a timer and compensation

Reserve, charge, wait three days, capture. If anything fails for good, completed compensable steps
are undone in reverse order and the process ends `COMPENSATED`.

```yaml
id: booking-saga
name: Booking with a cooling-off period
version: 1
steps:
  - id: start
    type: START
    name: Start
  - id: reserve
    type: ACTION
    name: Reserve the room
    topic: reserve-room
    preconditionStepId: start
    compensable: true
    compensationStepId: release
  - id: charge
    type: ACTION
    name: Charge the card
    topic: charge-card
    preconditionStepId: reserve
    retries: 2
    timeout: PT30S
    compensable: true
    compensationStepId: refund
  - id: cooling-off
    type: TIMER
    name: Three days to change your mind
    duration: P3D
    preconditionStepId: charge
  - id: capture
    type: ACTION
    name: Capture the payment
    topic: capture-payment
    preconditionStepId: cooling-off
  - id: release              # compensation: NO preconditions
    type: ACTION
    name: Release the room
    topic: release-room
  - id: refund
    type: ACTION
    name: Refund the card
    topic: refund
  - id: end
    type: END
    name: End
    preconditionStepId: capture
```

Test it: [testing.md](testing.md#saga-with-a-timer).

## 2. Human approval with an exclusive decision

`CHOICE` takes exactly one branch (longest guard first; the unguarded link is the default);
exclusive branches converge on a `JOIN` with `joinType: XOR`.

```yaml
id: expense-approval
name: Expense approval
version: 1
steps:
  - id: start
    type: START
    name: Start
  - id: approve
    type: USER_TASK
    name: Approve the expense
    formId: expense-approval          # a form with this id must exist in forms-engine
    preconditionStepId: start
    assignment:
      candidateGroups: [ "finance-${region}" ]
      priority: 70
      dueDate: P2D
    escalation:
      - after: PT48H
        action: reassign
        to: { candidateGroups: [ finance-managers ] }
  - id: decide
    type: CHOICE
    name: Approved?
    preconditionStepId: approve
  - id: pay
    type: ACTION
    name: Pay the expense
    topic: payments
    preconditions:
      - stepId: decide
        expression: "decision == 'APPROVE'"
  - id: notify-rejection
    type: ACTION
    name: Tell the employee
    topic: notifications
    preconditions:
      - stepId: decide                 # no expression = the default branch
  - id: done
    type: JOIN
    name: Done
    joinType: XOR
    preconditionStepIds: [ pay, notify-rejection ]
  - id: end
    type: END
    name: End
    preconditionStepId: done
```

The submitted form values (here `decision`) become process variables. In a test:
`runtime.completeUserTask(process, "approve", Map.of("decision", "APPROVE"))` (no forms-engine needed).
At runtime forms are loaded by `forms.directory-import.directories` or git import — NOT from
`classpath:/forms/`. Minimal form (`expense-approval.ecform`):

```json
{ "id": "expense-approval", "name": "Expense approval",
  "fields": [ { "id": "decision", "label": "Decision", "dataType": "string", "stereotype": "radio",
                "required": true,
                "options": [ { "value": "APPROVE", "label": "Approve" }, { "value": "REJECT", "label": "Reject" } ] } ] }
```

## 3. HTTP integration (no worker)

```yaml
id: charge-booking
name: Charge a booking over HTTP
version: 1
steps:
  - id: start
    type: START
    name: Start
  - id: charge
    type: HTTP_CALL
    name: Charge the card
    preconditionStepId: start
    retries: 2
    timeout: PT15S
    compensable: true
    compensationStepId: refund
    http:
      connection: payments               # workflow.http.connections.payments
      method: POST
      path: "/charges/${bookingId}"
      body:
        amount: "${amount * 1}"          # a single expression keeps its type
        reference: "Booking ${bookingId}"
      successStatus: [ 200, 201 ]
      output:                            # variable <- JEXL over {status, headers, body}
        chargeId: "body.id"
      retryOn: [ 5xx, io ]               # default; a 4xx is final
  - id: confirm
    type: ACTION
    name: Confirm the booking
    topic: bookings
    preconditionStepId: charge
  - id: refund
    type: HTTP_CALL
    name: Refund
    http:
      connection: payments
      method: POST
      path: "/charges/${chargeId}/refund"
  - id: end
    type: END
    name: End
    preconditionStepId: confirm
```

Needs `io.mateu.workflow:worker-http` (embedded) or the `http-worker-standalone-app` (kafka, topic
`http-calls`), plus `workflow.http.connections.payments.base-url` — see [security.md](security.md)
for auth profiles, `${secret:NAME}` and host binding. Every retry sends the same `Idempotency-Key`.

## 4. Started by a domain event, publishing one back

```yaml
id: booking-onboarding
name: Booking onboarding
version: 1
triggers:
  - id: on-booking-created
    destination: bookings                       # workflow.events.destinations.bookings.topic
    type: com.acme.booking.created              # '*' wildcards allowed
    filter: "data.channel == 'web'"
    businessKey: "booking-onboarding-${data.bookingId}"   # unique across workflows: include the workflow
    variables:
      bookingId: "${data.bookingId}"
      email: "${data.email}"
steps:
  - id: start
    type: START
    name: Start
  - id: welcome
    type: ACTION
    name: Send the welcome email
    topic: emails
    preconditionStepId: start
  - id: announce
    type: PUBLISH_EVENT
    name: Booking onboarded
    preconditionStepId: welcome
    event:
      destination: bookings-out
      type: com.acme.booking.onboarded
      key: "${bookingId}"
      payload:
        bookingId: "${bookingId}"
  - id: end
    type: END
    name: End
    preconditionStepId: announce
```

Config: `workflow.events.destinations.bookings.topic=bookings`,
`workflow.events.destinations.bookings-out.topic=bookings-out`. Kafka mode needs
`io.mateu.workflow:inbound-events-kafka`. Inject an event in any mode:
`POST /workflow/api/events/bookings` (a structured CloudEvent), MCP `publishInboundEvent`, or in a test
`runtime.publishEvent("bookings", "com.acme.booking.created", Map.of(...))`. Webhooks (GitHub, HMAC) →
the `webhook-gateway` turns them into events on a destination.

The same trigger can live outside the definition, in `src/main/resources/workflows/events.yaml`:

```yaml
- id: on-booking-created
  workflow: booking-onboarding
  destination: bookings
  type: com.acme.booking.created
  businessKey: "booking-onboarding-${data.bookingId}"
```

## 5. Request/response

**EC asks and waits** — one step sends the request and waits for the reply; the wait is armed in the
same transaction, so a fast reply is never lost (a `WAIT_FOR_MESSAGE` *after* the request can lose it).

```yaml
id: booking-quote
name: Ask for a quote and wait for it
version: 1
steps:
  - id: start
    type: START
    name: Start
  - id: ask-quote
    type: PUBLISH_EVENT                 # or HTTP_CALL with an http block
    name: Ask for a quote
    preconditionStepId: start
    timeout: PT5M                       # bounds request + reply
    retries: 0
    onTimeoutStepId: no-quote
    event:
      destination: quote-requests
      type: com.acme.quote.requested
      key: "${process.businessKey}"
      payload:
        requestId: "${process.businessKey}"   # correlation id chosen BEFORE sending
        item: "${item}"
    awaitReply:
      messageName: quote-ready
      correlationExpression: businessKey
      onEvent:                          # optional: the reply is a domain event
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
    name: No quote in time
    topic: booking
    preconditions:
      - stepId: ask-quote
        expression: "false"             # reached only through onTimeoutStepId
  - id: outcome
    type: JOIN
    name: Outcome
    joinType: XOR
    preconditionStepIds: [ book, no-quote ]
  - id: end
    type: END
    name: End
    preconditionStepId: outcome
```

Without `onEvent` the reply is a plain message: `POST /workflow/api/messages
{"messageName":"quote-ready","correlationKey":"<businessKey>","variables":{"price":"12"}}`.

**A caller waits over HTTP** (synchronous invocation): `syncInvocation.enabled` + a `REPLY`.

```yaml
id: book-trip
name: Book a trip and answer the caller
version: 1
syncInvocation:
  enabled: true
  onFailure: REPLY_IMMEDIATELY          # or REPLY_AFTER_COMPENSATION
  defaultDeadlineMs: 5000
steps:
  - id: start
    type: START
    name: Start
  - id: book
    type: ACTION
    name: Book
    topic: booking
    preconditionStepId: start
  - id: answer
    type: REPLY
    name: Answer the caller
    preconditionStepId: book
    replyVariables: [ bookingId ]
  - id: end
    type: END
    name: End
    preconditionStepId: answer
```

`POST /workflow/api/definitions/book-trip/invocations` with `Idempotency-Key` (required) and
`Prefer: wait=10` → 200 + reply, 202 + `Location` when the deadline passes (the process carries on),
502 when it failed first.

## 6. Fan-out with forEach (per-item compensation)

```yaml
id: group-booking
name: Reserve every room of a group booking
version: 1
steps:
  - id: start
    type: START
    name: Start
  - id: reserve
    type: ACTION
    name: Reserve a room
    topic: rooms
    preconditionStepId: start
    retries: 2
    compensable: true
    compensationStepId: release          # once per completed iteration, with its item
    forEach:
      items: rooms                       # a JSON-array variable, e.g. '["101","102"]'
      as: room
      mode: parallel                     # or sequential
      maxConcurrency: 5
      output: reservations               # JSON array, one element per item, in item order
      outputElement: reservationId
  - id: release
    type: ACTION
    name: Release a room
    topic: rooms
  - id: confirm
    type: ACTION
    name: Confirm the group
    topic: bookings
    preconditionStepId: reserve
  - id: end
    type: END
    name: End
    preconditionStepId: confirm
```

Each iteration is its own task (own retries/timeout); the worker sees `room` and `index`. Iteration
outputs are NOT merged into the process — read `reservations`. Limit: `workflow.foreach.max-items` (1000).

## 7. Poll until done (repeat)

```yaml
id: payment-settlement
name: Wait until a payment settles
version: 1
steps:
  - id: start
    type: START
    name: Start
  - id: poll
    type: HTTP_CALL
    name: Poll the payment
    preconditionStepId: start
    http:
      connection: payments
      path: "/payments/${paymentId}"
      output:
        paymentStatus: "body.status"
    repeat:
      until: "paymentStatus == 'SETTLED'"
      maxIterations: 20                  # required; exceeding it fails the step
      delay: PT30S
  - id: end
    type: END
    name: End
    preconditionStepId: poll
```

## 8. Agent with tools

```yaml
id: support-triage
name: Triage a support ticket
version: 1
steps:
  - id: start
    type: START
    name: Start
  - id: triage
    type: AGENT_CALL
    name: Triage the ticket
    preconditionStepId: start
    timeout: PT60S                       # per model turn
    retries: 2
    agent:
      connection: claude                 # workflow.llm.connections.claude (key lives there)
      goal: "Customer ${customerId} wrote: ${message}. Decide whether a refund is due."
      tools:
        - name: lookupOrder
          description: Look up an order by its id.
          http: { connection: orders, method: GET, path: "/orders/${orderId}" }
          inputSchema:
            type: object
            required: [ orderId ]
            properties: { orderId: { type: string } }
        - name: notifyCustomer
          description: Email the customer.
          step: notify-customer          # a step used ONLY as a tool
          approval: { formId: approve-email, candidateGroups: [ support-leads ] }
      output:
        schema: { type: object, required: [ decision ], properties: { decision: { type: string } } }
        mapping: { decision: "json.decision" }
      maxSteps: 6                        # required budget
      maxTokens: 20000
  - id: notify-customer
    type: ACTION
    name: Notify the customer
    topic: emails
    retries: 3
  - id: end
    type: END
    name: End
    preconditionStepId: triage
```

Every model turn (`agent-turn@1`) and tool call is a child step execution with its own
retries/timeouts/audit. Needs `worker-llm` + a Spring AI provider. Budgets fail the step:
`AGENT_MAX_STEPS`, `AGENT_MAX_TOKENS`, `AGENT_TIMEOUT`. A single model call without tools: `LLM_CALL`:

```yaml
- id: classify
  type: LLM_CALL
  name: Classify the complaint
  preconditionStepId: start
  retries: 2
  llm:
    connection: claude
    system: "You classify customer complaints."
    prompt: "Complaint: ${message}"
    responseSchema: { type: object, required: [ category ], properties: { category: { type: string } } }
    output: { category: "json.category" }
```

## 9. Updates of a running process

Validated writes that answer with a result (unlike fire-and-forget messages).

```yaml
id: order-fulfilment
name: Order fulfilment
version: 1
steps:
  - id: start
    type: START
    name: Start
  - id: awaitShipping
    type: WAIT_FOR_MESSAGE
    name: Await shipping approval
    messageName: ship-order
    correlationExpression: businessKey
    preconditionStepId: start
  - id: ship
    type: ACTION
    name: Ship
    topic: shipping
    preconditionStepId: awaitShipping
  - id: end
    type: END
    name: End
    preconditionStepId: ship
updates:
  - name: changeAddress
    accepts:                              # JSON Schema of the payload -> 400
      type: object
      required: [ address ]
      properties: { address: { type: string, minLength: 5 } }
    acceptedWhile: [ awaitShipping ]      # otherwise 409
    validate:                             # first false guard -> 422
      - expression: "payload.address != address"
        message: That is already the shipping address.
    set:
      address: payload.address
    reply: "{'address': address}"
  - name: approveShipping
    completes: awaitShipping              # resumes the waiting step
    set: { approvedBy: payload.by }
    reply: approvedBy
```

`POST /workflow/api/processes/{id}/updates/changeAddress` (or `/processes/by-key/updates/{name}?businessKey=`)
→ 200 `{status: COMPLETED, result}`. Start-or-update in one call:
`POST /workflow/api/definitions/order-fulfilment/update-with-start {businessKey, variables, update, payload}`.

## 10. Long-lived and flow-controlled

```yaml
id: monthly-billing
name: Monthly billing per customer
version: 1
businessKeyPolicy: allowAfterFinished    # unique | allowAfterFinished | allowAfterFailed | terminateRunning
startPolicy:
  key: "${customerId}"
  singleton: skip                        # skip | replace
  throttle: { limit: 10, period: PT1M, onLimit: queue }   # queue | drop
retention:
  completed: P30D
  error: P180D
searchAttributes:
  customerId: "${customerId}"
  amount: { value: "${amount}", type: number }
tags: [ billing, "team:payments" ]
steps:
  - id: start
    type: START
    name: Start
  - id: bill
    type: ACTION
    name: Bill the month
    topic: billing
    priority: 80                         # 0-100, default 50
    preconditionStepId: start
  - id: wait-month
    type: TIMER
    name: Wait for next month
    duration: P30D
    preconditionStepId: bill
  - id: next-month
    type: CONTINUE_AS_NEW                # fresh run, same business key, bounded history
    name: Next month
    preconditionStepId: wait-month
    continueAsNew:
      version: latest                    # same (default) | latest
      variables: [ customerId ]
```

Worker rate limit per topic (config): `workflow.workers.rate-limits.billing.permits=5`,
`workflow.workers.rate-limits.billing.period=PT1S`.

## 11. Child process

```yaml
id: customer-onboarding
name: Customer onboarding
version: 1
steps:
  - id: start
    type: START
    name: Start
  - id: kyc
    type: PROCESS
    name: Run KYC
    childWorkflowDefinitionId: kyc-check     # another definition; gets ALL parent variables
    outputVariables: [ kycResult ]          # only these come back
    timeout: P1D
    preconditionStepId: start
  - id: end
    type: END
    name: End
    preconditionStepId: kyc
```

## 12. Sensitive data

```yaml
id: card-payment
name: Card payment
version: 1
sensitiveVariables: [ cardNumber, "patient.*" ]   # needs workflow.encryption.keys.<id>
steps:
  - id: start
    type: START
    name: Start
  - id: pay
    type: ACTION
    name: Pay
    topic: payments
    preconditionStepId: start
  - id: end
    type: END
    name: End
    preconditionStepId: pay
```
