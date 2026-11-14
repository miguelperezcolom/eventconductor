---
title: Agents in workflows
description: Invoke a published IA catalogue agent with explicit input and durable execution.
---

An `AGENT` step invokes an agent already published from the IA catalogue's Git repository. The
catalogue owns its system prompt, model, allowed MCP/RAG/peer references and JSON output schema.
The workflow chooses the task and explicit input. Credentials never belong in a definition file.

```yaml
- id: assess-case
  type: AGENT
  name: Assess synthetic case
  agent:
    ref: workflow-case-assessor
    taskPrompt: Assess this case using the available evidence.
    input:
      caseId: "${caseId}"
      case: "${caseData}"
    outputVariable: assessment
  timeout: 300000
  retries: 0
  preconditionStepId: start
```

`agent.ref` and `agent.outputVariable` are required. `retries` must be `0` for AGENT: a worker failure
is final. Durable recovery and message redelivery retain the same invocation and confirmed tool
results; they do not repeat a failed paid model call. Explicitly restarting a stopped process
creates a new invocation. Input defaults to `{}`; capability selections
`mcp`, `rag`, `peers` inherit when absent and disable when `[]`. Selections can only narrow the
published agent's capabilities. The graphical editor selects a published agent and edits
the task prompt, input and output variable. MCP, RAG and peers are displayed read-only from
the catalogue. Existing capability overrides remain active until the author explicitly chooses
**Use catalogue configuration** or selects another agent. In VS Code, run **EventConductor:
Configure Agent Catalogue** to set the endpoint and store its credentials in SecretStorage.
Definition import fails if the catalogue cannot be read or a reference/selection is invalid.
The Maven plugin validates the file's structure and expressions; it does not contact the catalogue.

The engine sends the generic `run-agent@1` task on `agent-calls`. Its only task variable is
`__agent`, containing the rendered explicit input, instruction, selections, output variable,
deadline, timeout budget and audit initiator. A retry retains the same invocation ID and rendered
request. Restarting a stopped process creates a new invocation. The worker validates the final
JSON schema and returns only the output variable. Read structured results in a guard with
`json:parse(assessment).decision == 'accepted'`.

Configure catalogue access on the engine with `WORKFLOW_AGENTS_CATALOGUE_URL`,
`WORKFLOW_AGENTS_TOKEN_URI`, `WORKFLOW_AGENTS_CLIENT_ID` and `WORKFLOW_AGENTS_CLIENT_SECRET`.
The catalogue adapter authenticates using client credentials. The editor's same-origin
`GET /api/workflow-agents` requires an authenticated caller and the gateway's editor permissions.
Keep that route behind the authenticated gateway when embedding the engine.

`REVIEW_REQUIRED` pauses the process when an external effect has an uncertain outcome. Automatic
retry, compensation and manual resume/restart remain held until the runtime's authenticated
reconciliation resolves it. A resumed non-timeout invocation renews its configured execution
budget; business deadlines remain absolute. An engine timeout first requests correlated
cancellation and waits for the worker to confirm effects are known; safe cancellation then
becomes native `TIMEOUT`, including `onTimeoutStepId` routing. No business variable is written
while held or on invalid JSON.

The updated engine and durable IA worker must be deployed together. Agent replies echo
`invocationId`, monotonically increasing `stateSequence`, and `reviewResolved: true` only when
reconciliation resumes a held invocation. Ordinary worker messages preserve their legacy shape.

## Sensitive variables

What the agent receives leaves the engine for its runtime and, through it, a model and its tools. In a definition
that declares [sensitive variables](/guides/sensitive-data-and-encryption/), an `AGENT` step whose `taskPrompt` or
`input` reads one is refused at load (and by the Maven plugin) unless it says `allowSensitiveVariables: true`:

```yaml
agent:
  ref: claims-assessor
  taskPrompt: "Assess the claim of ${patient.name}"
  input: { history: "${patient.history}" }
  outputVariable: assessment
  allowSensitiveVariables: true
```

Without the opt-in, sensitive variables are also left out of the request's context, so an indirect read cannot reach
them. With it, `__agent` is sealed in the step's row, the outbox and the `agent-calls` message, and the agent runtime
needs the keys to open it.

## forEach

An `AGENT` step can declare a [`forEach`](/guides/loops-and-multi-instance/): each item is an invocation of its own,
with its own invocation id, and its request is rendered with the iteration's `item` and `index`. Each result is
collected into the forEach's `output` as usual.

## Operating a process that waits on an agent

While an invocation is under review (`REVIEW_REQUIRED`, the process `PAUSED`), completing a step by hand, retrying
from a step and migrating are refused with `409`, as are resume, retry and restart. Only the runtime's correlated
reconciliation releases a hold. Setting variables is allowed. While an invocation is in flight, it is not completed,
reset, migrated or restarted by hand; cancel the process instead. See
[Operating running processes](/guides/operating-running-processes/#agent-invocations).

`GET /workflow/api/processes/{id}/steps` shows each AGENT step's `agent`: its `invocationId`, `underReview`, the
engine's `holdReason` (`TIMEOUT`, `CANCELLED`, `END`) and the last `lastStateSequence` accepted. The MCP
`getProcessDetails` tool shows the invocation id and hold reason of each step.

## Testing

The [test kit](/guides/testing-workflows/#agents) never contacts the catalogue or a runtime. Every reference is taken
as published, and `runtime.whenAgent("assess")` scripts the runtime's answers: `thenOutput(json)`,
`thenReviewRequired()` (released with `runtime.resolveAgentReview(process, "assess")`), `thenFail(code, message)`,
`thenCancelled()` and `thenNoReply()`.
