---
title: Agents with Tools
description: The AGENT_CALL step — a language model that works towards a goal by calling tools, where every tool call is a real, audited step execution with retries, timeouts, compensation and cancellation, bounded by step, token and time budgets.
---

`AGENT_CALL` lets a language model work towards a goal by calling tools: it reads the goal, asks for a
tool, sees the result, asks for another, and finally answers. What makes it different from an agent
framework running inside a worker is that **every tool call is a step execution of the engine**:

- it is dispatched, timed out, retried and cancelled like any step — a flaky API is retried per the
  tool's own `retries`, a tool at a worker is cancelled with the process;
- it is **audited**: each model turn and each tool call is a row of the process, with its arguments,
  its result, its attempts and its timing, visible in the process view, REST and MCP;
- it takes part in the **saga**: a tool step that is `compensable` is compensated, once per call,
  newest first, when the process fails later;
- it is **bounded**: `maxSteps` (required) caps the tool calls, `maxTokens` the tokens, `maxDuration`
  the wall clock — past any of them the step fails, loudly;
- the model can only call what the definition **allows**, with arguments **validated** against each
  tool's JSON Schema before anything runs, and a person can be asked to **approve** a call first.

It is built on [LLM Calls](/guides/llm-calls/) (the same connections, providers and metrics) and on
the iteration model of [Loops and Multi-Instance](/guides/loops-and-multi-instance/): the agent's own
execution is a container, its turns and tool calls are its children.

## A support-triage agent

A customer writes in. The agent looks the order up over HTTP, opens a refund through a sub-process — a
support lead approves each refund first — tells the customer, and says what it decided:

```yaml
id: support-triage
name: Triage a support ticket
version: 1
steps:
  - { id: start, type: START, name: Start }
  - id: triage
    type: AGENT_CALL
    name: Triage the ticket
    preconditionStepId: start
    timeout: PT60S                       # per model call
    retries: 2                           # per model call (429, 5xx, timeouts; a 4xx is final)
    agent:
      connection: claude                 # workflow.llm.connections.claude
      system: You are a support agent for an online shop. Use the tools; never guess an order.
      goal: "Customer ${customerId} wrote: ${message}. Decide whether a refund is due and open it if so."
      tools:
        - name: lookupOrder
          description: Look up an order by its id.
          http: { connection: orders, method: GET, path: "/orders/${orderId}" }
          inputSchema:
            type: object
            required: [orderId]
            properties: { orderId: { type: string } }
        - name: openRefund
          description: Open a refund for an order. A support lead approves it first.
          process: refund-flow           # a child process per call
          outputVariables: [refundId]    # what the child hands back as the result
          approval: { formId: approve-refund, candidateGroups: [support-leads] }
          inputSchema:
            type: object
            required: [orderId, amount]
            properties: { orderId: { type: string }, amount: { type: number, minimum: 0 } }
        - name: notifyCustomer
          description: Email the customer.
          step: notify-customer          # a step of this definition, used only as a tool
      output:
        schema: { type: object, required: [decision], properties: { decision: { type: string } } }
        mapping: { decision: "json.decision" }
      maxSteps: 6
      maxTokens: 50000
      maxDuration: PT30M
  - { id: end, type: END, name: End, preconditionStepId: triage }
  - id: notify-customer
    type: ACTION
    name: Notify the customer
    topic: emails
    retries: 3
```

A run of it, as the process view shows it:

| Step | What happened |
|---|---|
| `triage · turn 1` | The model reads the goal and calls `lookupOrder {"orderId": "O-1"}` |
| `triage.lookupOrder · lookupOrder call (turn 1)` | `GET /orders/O-1` on the `orders` connection → `{status: 200, body: {...}}` |
| `triage · turn 2` | The model calls `openRefund {"orderId": "O-1", "amount": 20}` and `notifyCustomer {...}` |
| `triage.openRefund.approval · openRefund approval (turn 2)` | A task for `support-leads` on the `approve-refund` form — `approved: true` |
| `triage.openRefund · openRefund call (turn 2)` | A `refund-flow` child process → `{refundId: "RF-1"}` |
| `notify-customer · notifyCustomer call (turn 2)` | The `emails` worker, with `text` as a variable |
| `triage · turn 3` | The model answers `{"decision": "refund"}` → the process variable `decision` |

The **Agent** tab of the process shows the transcript: the goal, each model message, each call with
its arguments and each result — values under keys like `password`, `token`, `secret`, `apiKey` or
`authorization` are shown as `***`.

## The `agent` block

| Field | | |
|---|---|---|
| `connection` | **required** | A named connection, `workflow.llm.connections.<name>` — see [LLM Calls](/guides/llm-calls/#connections) |
| `goal` | **required** | The first user message — a [text template](/guides/payload-templates/), rendered once when the step starts |
| `tools` | **required** | The allowlist of tools, at least one (below) |
| `maxSteps` | **required** | The most tool calls over the whole loop |
| `system` | | The system prompt — a text template, rendered once |
| `model` | | Overrides the connection's model |
| `temperature` | | 0–2; sent only when set |
| `output` | | `schema` (JSON Schema the final answer must match), `mapping` (variable ← JEXL over `{text, json, usage, turns, toolCalls}`), `variable` (receives the text, or the JSON document) |
| `maxTokens` | | The most input + output tokens over every turn |
| `maxDuration` | | ISO-8601 wall clock for the whole loop (`PT10M`) |
| `toolConcurrency` | | How many tool calls of one turn run at once; default `1` |
| `onToolError` | | `fail` (default) or `report` — see [Failures](#failures) |

The step's own `timeout` and `retries` apply to **each model call**; each tool has its own.

### Tools

Each tool has a `name` (what the model calls it, `[A-Za-z0-9_-]{1,64}`, unique), a `description` (all
the model knows about it — write it for the model), an optional `inputSchema`, and exactly one of:

| Kind | Runs as | Its result |
|---|---|---|
| `step: <stepId>` | That step of the definition — any of ACTION, HTTP_CALL, LLM_CALL, RULE, PROCESS, USER_TASK — with its own timeout, retries, topic and compensation. The step must be used **only** as a tool: no preconditions, nobody's precondition, compensation or on-timeout target | What it reports |
| `http: {…}` | An `HTTP_CALL` block; templates see the arguments (`${orderId}`). It runs on the `http-call` worker, so [allowed hosts and host-bound credentials](/guides/http-calls/) apply | Its `output` mapping, or `{status, body}` |
| `process: <definitionId>` | A child process per call, started with the process variables and the arguments | Its `outputVariables` |
| `task: <id>@<version>` | An `ACTION` on that task contract (`topic` optional) | What the worker reports |

`http` and `task` tools take the agent step's `timeout` and `retries`; a `process` tool has no timeout.

**Arguments.** The model's arguments must be a JSON object valid against the tool's `inputSchema`
(JSON Schema 2020-12). Each top-level argument is laid over the process variables **for that call
only** — a worker receives `orderId` as a variable, a template reads `${orderId}`, typed. A call to a
tool that is not declared, or with arguments that do not validate, is **not run**: the model gets the
reason as that call's result, and the call counts against `maxSteps`.

**Results.** A tool never writes the process's variables — parallel calls would race, and the model,
not the tool, decides what matters. What it reports is kept on its execution and handed to the model
as JSON (at most 16 000 characters). Only the final answer's `output` writes the process.

**Approval.** With `approval: {formId, assignee?, candidateUsers?, candidateGroups?}`, each call first
creates a `USER_TASK` on that form (with the tool's arguments as its variables) through the forms
engine. When it is completed, its `approved` field decides: truthy, the tool runs; otherwise it does
not, and the model is told so — with the form's `comment`, if any.

## How it runs

1. The step starts: `system` and `goal` are rendered from the process; the agent's execution becomes
   a container, `PENDING`.
2. **A model turn** is a child execution dispatched to the built-in `agent-turn@1` task (`worker-llm`,
   on topic `llm-calls` in kafka mode), carrying the conversation so far and the tool specs. The tools
   are declared to the provider but never run by it.
3. If the model asks for tools, each accepted call becomes a child execution (an approval task first,
   when the tool needs one), started up to `toolConcurrency` at once.
4. When every call of the turn has a result, the next turn is created with the conversation rebuilt
   from the children: the goal, each turn's message and calls, each call's result.
5. When the model answers without calling a tool, the answer is checked against `output.schema`,
   mapped through `output`, and the step completes.

**There is no state in memory.** Every decision is made from the children as they are stored, so an
engine that dies anywhere in the loop — mid-turn, with tools at their workers — resumes on the next
step-over on any node, with the same decision. A late reply from a tool's worker is taken by whichever
node is up.

**Cancellation.** Cancelling the process cancels the agent and every turn and tool call still going —
a cancellation is sent to each worker holding one, and a `process` tool's child process is cancelled.

## Failures

| What | Code | |
|---|---|---|
| More tool calls than `maxSteps` | `AGENT_MAX_STEPS` | Final: the step ends `ERROR` |
| More tokens than `maxTokens` | `AGENT_MAX_TOKENS` | Final, checked after every turn |
| Longer than `maxDuration` | `AGENT_TIMEOUT` | The step ends `TIMEOUT` — `onTimeoutStepId` routes it |
| A model call fails for good | `AGENT_MODEL_FAILED` | After the step's `retries`; a provider 4xx is not retried |
| The final answer does not match `output.schema` | `LLM_INVALID_OUTPUT` | On the turn, retried per `retries` |
| A tool fails for good | `AGENT_TOOL_FAILED` | After the tool's own `retries`, with `onToolError: fail` (the default); with `report`, the model is told the tool failed and carries on |

A budget failure is final — retrying would only hit the same budget — so the process fails and the
saga engages: every completed call of a `compensable` `step:` tool is compensated, newest first, each
compensation seeing that call's arguments and what it reported:

```yaml
  - id: open-refund
    type: ACTION
    name: Open a refund
    compensable: true
    compensationStepId: cancel-refund    # runs once per completed openRefund call, with its orderId and refundId
  - { id: cancel-refund, type: ACTION, name: Cancel a refund }
```

An operator can retry a failed agent from the process view: the completed turns and calls stand, the
failed ones run again.

## Testing an agent

The [test kit](/guides/testing-workflows/) scripts the model — `whenAgent(stepId)` — and the tools are
answered by the ordinary stubs. A test never calls a provider.

```java
@EventConductorTest
class SupportTriageTest {

    @Test
    void aRefundIsOpenedOnceALeadApprovesIt(WorkflowTestRuntime runtime) {
        var model = runtime.whenAgent("triage")
                .thenCallTool("lookupOrder", Map.of("orderId", "O-1"))
                .thenCallTools(AgentStub.call("openRefund", Map.of("orderId", "O-1", "amount", 20)),
                               AgentStub.call("notifyCustomer", Map.of("text", "Your refund is on its way")))
                .thenAnswer("{\"decision\":\"refund\"}");
        runtime.whenHttpCall("triage.lookupOrder").thenRespond(200, "{\"status\":\"DELIVERED\",\"total\":20}");
        runtime.whenStep("issue-refund").thenComplete(Map.of("refundId", "RF-1"));   // a step of refund-flow
        runtime.whenTask("emails").thenComplete(Map.of("sent", true));

        var process = runtime.start("support-triage", "T-1", Map.of("customerId", "C-7", "message", "broken mug"));
        runtime.completeUserTask(process, "triage.openRefund.approval", Map.of("approved", true));

        assertThat(process).isCompleted().hasVariable("decision", "refund");
        assertThat(model.lastToolResults().get(0).get("content")).isEqualTo(Map.of("refundId", "RF-1"));
    }
}
```

| | |
|---|---|
| `thenCallTool(name, args)` | The turn asks for one tool call |
| `thenCallTools(call(name, args), …)` | The turn asks for several (run up to `toolConcurrency` at once) |
| `thenAnswer(text)` | The final answer (JSON for a step with an `output.schema`) |
| `thenFail(code, retryable)` | The model call fails |
| `costing(in, out)` | The tokens the following turns report — to test `maxTokens` |
| `requests()`, `lastRequest()`, `lastToolResults()` | What the model was sent: system, tools, messages, results |

Tool step ids: a `step:` tool is its step's id (`whenStep("notify-customer")`); an `http`, `process`
or `task` tool is `<agentStepId>.<toolName>` (`whenHttpCall("triage.lookupOrder")`); an approval is
`<agentStepId>.<toolName>.approval`.

## Observability

- **Process view**: the Steps tab lists every turn and call; the **Agent** tab shows the transcript; the
  diagram draws the agent's `step:` tools on dotted lines and a `turns↻ calls⚒` badge.
- **REST** `GET /workflow/api/processes/{id}/steps` and **MCP** `getProcessDetails`: the agent's execution carries
  `agent: {state, turns, toolCalls, inputTokens, outputTokens, totalTokens, lastMessage}`; the turns and
  calls are executions with it as `parentExecutionId`. MCP `getAgentTranscript(stepExecutionId)` returns
  the whole transcript.
- **Metrics**: each turn is an `eventconductor.llm.call` / `eventconductor.llm.tokens` sample tagged
  with the agent's step, plus Spring AI's `gen_ai.client.*`; each tool call is a run of its step in
  [analytics](/guides/analytics/).
- The transcript is kept on the agent's execution, capped at 64 KB (each entry at 2 000 characters,
  the oldest turns elided first) and redacted. Prompts, arguments and answers are never logged.
