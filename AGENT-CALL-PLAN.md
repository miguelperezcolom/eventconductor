# Plan: `AGENT_CALL` step — a model that uses tools, every tool call a real step execution

> Status: **DECIDED (2026-10-19)** — implementation on `feat/agent-call`.
> Builds on `LLM_CALL` (`LLM-CALL-STEP-PLAN.md`), the forEach container/iteration model of #543
> (`LOOPS-AND-FOREACH-PLAN.md`) and the test kit of #548.

## 1. Goal

Camunda's AI Agent lets a model choose activities in a loop while the engine handles retries and
incidents. EventConductor does the same, with one difference that matters to anyone who has to
audit, debug or roll back what an agent did: **every tool the model uses is a real step execution** —
dispatched, timed out, retried, cancelled and compensated by the engine exactly like a step the
definition draws, visible in the process view, counted in analytics, bounded by budgets declared in
the definition.

```yaml
- id: triage
  type: AGENT_CALL
  name: Triage the support ticket
  preconditionStepId: start
  timeout: PT60S                         # per model call
  retries: 2                             # per model call (429/5xx/timeouts; a 4xx is final)
  agent:
    connection: claude                   # workflow.llm.connections.claude
    # model: claude-opus-5-5
    system: "You are a support agent for an online shop. Use the tools; never guess an order."
    goal: |
      Customer ${customerId} wrote: ${message}
      Decide whether a refund is due and open it if so.
    tools:
      - name: lookupOrder
        description: Look up an order by its id.
        http: { connection: orders, method: GET, path: "/orders/${orderId}" }
        inputSchema:
          type: object
          required: [orderId]
          properties: { orderId: { type: string } }
      - name: openRefund
        description: Open a refund for an order. Needs a supervisor's approval.
        process: refund-flow             # a child process per call
        outputVariables: [refundId]
        approval: { formId: approve-refund, candidateGroups: [support-leads] }
        inputSchema:
          type: object
          required: [orderId, amount]
          properties: { orderId: { type: string }, amount: { type: number, minimum: 0 } }
      - name: notifyCustomer
        description: Email the customer.
        step: notify-customer            # a template step of this definition, used only as a tool
    output:
      schema: { type: object, required: [decision], properties: { decision: { type: string } } }
      mapping: { decision: "json.decision", agentSummary: "text" }
    maxSteps: 8                          # required: the most tool calls in the whole loop
    maxTokens: 50000                     # optional: input+output tokens over every turn
    maxDuration: PT10M                   # optional: wall clock for the whole loop
    toolConcurrency: 1                   # optional: tool calls of one turn run at most N at once
    onToolError: fail                    # fail (default) | report — hand the error to the model instead
```

## 2. Decisions

| # | Decision |
|---|---|
| A | **One nested `agent:` block**, like `llm:`; type `AGENT_CALL`. |
| B | **The agent step is a container** (the #543 model). Its children are step executions: one per **model turn** (`agent-turn@1`, run by `worker-llm` on topic `llm-calls`, so provider retries/metrics/timeouts apply per turn) and one per **tool call** (the tool's own step: its timeout, retries, topic, compensation). No DYNAMIC injection: the container pass already gives idempotent, crash-safe materialisation, per-child fencing (`dispatchCount`) and cascade-on-cancel; `StepsInjected` adds top-level steps to the precondition graph, which is not what a tool call is. |
| C | **No state in memory.** The loop is a pure function of the persisted children: the conversation is rebuilt, each pass, from the turns' recorded answers and the tools' recorded outputs (`outputJson`). A crash at any point resumes from the database: the next step-over redoes the same decision. |
| D | **Tools are an allowlist in the definition**: `step` (a template step of the definition that the normal flow never reaches), `http` (an `HTTP_CALL` block — HostGuard, allowed hosts and host-bound credentials apply because it runs on the `http-call` worker), `process` (a child process, `outputVariables` as its result), `task` (an `ACTION` with that task contract). Exactly one per tool. |
| E | **Arguments are validated before anything runs**: the call must name a declared tool, its arguments must be a JSON object valid against the tool's `inputSchema` (JSON Schema 2020-12). A call that fails either is not executed; the model gets the error as that call's result (and it counts against `maxSteps`). |
| F | **Arguments reach the tool as its scope**: each top-level argument is laid over the process variables for that execution only (templates see them typed: `${orderId}`), like a forEach item. **A tool never writes process variables**: what it reports stays on its execution and becomes the model's tool result. Only the final answer's `output.mapping` writes the process. |
| G | **Budgets fail loud and final** (a retry would hit the same cap): `maxSteps` → `AGENT_MAX_STEPS`, `maxTokens` → `AGENT_MAX_TOKENS` (checked after every turn), `maxDuration` → `AGENT_TIMEOUT` (the step ends `TIMEOUT`, so `onTimeoutStepId` routes it). The engine also caps the turns at `maxSteps + 1`. A model call failing for good (its `retries` spent, or a final 4xx) ends the step `ERROR` (`AGENT_MODEL_FAILED`). An answer that does not match `output.schema` fails the turn `LLM_INVALID_OUTPUT` — retryable, the step's `retries` apply. |
| H | **Tool failures**: `onToolError: fail` (default) — a tool that fails for good (its own retries spent) cancels the in-flight siblings and fails the agent step (`AGENT_TOOL_FAILED`); the process fails and the saga compensates. `report` — the error is the model's tool result and the loop goes on. |
| I | **Compensation follows the normal rules**: a `step:` tool that is `compensable` with a `compensationStepId` is compensated once per completed call, newest first, each compensation seeing that call's arguments and what it reported (the #543 multi-unit compensation). Model turns are not compensable (an `AGENT_CALL` cannot be `compensable`). |
| J | **Human approval per tool** (`approval: {formId, assignee?, candidateUsers?, candidateGroups?}`): before the tool runs, a `USER_TASK` child is created through the forms engine with the tool, the arguments and the call id in its scope. The form's `approved` field decides: truthy → the tool runs; otherwise the model is told the call was rejected (with the form's `comment`, if any). |
| K | **Transcript on the agent's execution**: the container's `outputJson` holds `{transcript, turns, toolCalls, inputTokens, outputTokens}`, rewritten after every change, redacted (values under keys like `password`, `token`, `secret`, `apiKey`, `authorization` become `***`) and capped (each entry 2 000 chars, the whole 64 KB, oldest entries elided first). The process view, REST and MCP read it from there. No new column, **no Flyway migration**. |
| L | **What the model sees of a tool result** is what the tool reported, as JSON, capped at 16 000 chars. An HTTP tool without `output` reports `{status, body}`. |
| M | **Worker contract**: `agent-turn@1` receives `__agent` = `{connection, model, system, messages, tools, responseSchema, temperature}` and answers `__agentTurn` = `{text, toolCalls: [{id, name, arguments}], model, finishReason, usage}`. Tool calling through Spring AI `ToolCallingChatOptions` with definition-only callbacks — Spring AI 2.0 chat models never execute tools themselves, so the engine stays the only executor. |
| N | **`toolConcurrency`** (default 1) bounds how many tool calls *of one turn* run at once; the next turn starts only once every call of the current one has a result. |
| O | **Test kit**: `whenAgent("triage")` scripts the turns — `.thenCallTool("lookupOrder", Map.of("orderId", "O-1"))`, `.thenCallTools(call(...), call(...))`, `.thenAnswer(text)`, `.thenFail(code, retryable)` — and records the requests (`requests()`, `lastRequest()` with the messages and tool results the model would have seen). Tools are answered by the ordinary stubs (`whenStep`, `whenHttpCall("triage.lookupOrder")`, `whenTask`, `completeUserTask`). A real provider is never called. |

## 3. Engine

- `Step.agent` (`AgentCall`, `AgentTool`, `ToolApproval`, `AgentOutput`); `StepType.AGENT_CALL`.
- `StepExecution`: a top-level `AGENT_CALL` execution is a container (`isContainer`); a child whose
  scope says `__agentKind=turn` dispatches `agent-turn@1` with `__agent`; default topic `llm-calls`.
- `AgentLoop` (domain service, pure): `start(container)` renders `system`/`goal` once into the
  container's scope; `advance(container, children)` decides — start CREATED children within
  `toolConcurrency`; after a completed turn: final answer → map `output`, complete; tool calls →
  validate, materialise one child per accepted call (or an approval task first); every call of the
  turn resolved → materialise the next turn with the rebuilt conversation; budgets; failures.
  Called from `WorkflowOrchestrationService`'s container pass, like `advanceContainer`.
- Child step ids: a turn is the agent's step id; a `step:` tool is the template step's id (so
  `whenStep`, workers resolving by step id, and compensation all work unchanged); `http`/`process`/
  `task` tools are `<agentStepId>.<toolName>`; an approval is `<agentStepId>.<toolName>.approval`.
- `StepTimeoutDefaults` applies the default step timeout to `AGENT_CALL` (its turns);
  `HttpTaskRouting` knows `agent-turn@1`; `StartStepExecutionUseCase` maps the task id.

## 4. Validation (shared: `definition-analysis` `AgentCallRules`, engine + Maven plugin)

`connection`, `goal`, `tools` (≥ 1), `maxSteps` ≥ 1 required; `maxTokens` ≥ 1, `toolConcurrency` ≥ 1,
`onToolError` ∈ {fail, report}, `maxDuration` ISO-8601; tool names unique and `[A-Za-z0-9_-]{1,64}`
(what both providers accept); a description; exactly one of `step`/`http`/`process`/`task`; a `step`
tool's step exists, is not reached by the normal flow (no preconditions, nobody's precondition, not a
compensation or on-timeout target), is a type that does work (ACTION, HTTP_CALL, LLM_CALL, RULE,
PROCESS, USER_TASK) and carries no loop; `inputSchema` an object schema (`type: object`), and — engine and plugin — valid
JSON Schema; `output.schema` likewise; `output.mapping` variable names; no `${secret:…}` and no
credential keys; `AGENT_CALL` not `compensable`, no `forEach`/`repeat`/`loopBack`. Templates
(`system`, `goal`, `http` tool templates) and `output.mapping` expressions parse.

## 5. UI and tooling

- Process detail: an **Agent** tab with the transcript (turn, role, content, tool call, result); the
  Steps tab names children "triage · turn 2", "lookupOrder · call 3"; the diagram overlay carries
  `agent: {turns, toolCalls, tokens}` for the badge.
- Graph editor: `AGENT_CALL` node (indigo, "agent" glyph), `connection · N tools` subtitle, read-only
  "Agent" field listing the tools; dashed "tool" edges from the agent to its `step:` tools.
- REST `StepView` / MCP `StepSummary` gain `agentSummary` (turns, tool calls, tokens, last message).
- Schema: `agent` block, enum, `if type == AGENT_CALL then required agent`.

## 6. Tests

Unit: `AgentCallRulesTest`, `AgentLoopTest` (state machine: final answer, tool calls, rejection,
budgets, approvals, concurrency, transcript redaction/cap), `SpecValidatorTest`, worker-llm
`AgentTurnHandlerTest` (stub model) and per-provider mapping against a mocked HTTP server (Anthropic
`tool_use`/`tool_result`, OpenAI `tool_calls`/`tool` messages). E2E memory + JPA with the test kit:
tool calls, parallel tools, caps, crash/resume (a fresh step-over over persisted state), cancellation
mid-loop, compensation of tool steps, approval. Browser: the editor renders the agent node.

## 7. Deferred

- Streaming, images/documents as input, MCP servers as tool sources.
- `approval: required` with a built-in form (today the definition names its form).
- A whole-loop deadline enforced by the scheduler (today `maxDuration` is checked on every
  step-over of the process, which happens at least once per turn and per tool result).
- Per-turn `maxTokens` (today the connection's).
