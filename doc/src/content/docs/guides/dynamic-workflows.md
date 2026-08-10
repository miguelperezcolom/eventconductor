---
title: Dynamic Workflows
description: A DYNAMIC step whose worker returns new steps to add to the running process — deciding a flow's shape at runtime, add-only and bounded.
---

Most workflows are a fixed graph: you author the steps and their relations, and every process
instance runs that same shape. A **dynamic workflow** is one that decides part of its own shape at
runtime — a fan-out whose width is only known once the work starts, a plan a worker computes and
then executes, a set of sub-tasks discovered from the input.

EventConductor expresses this with one new step type. A `DYNAMIC` step is dispatched to a worker
like an `ACTION`, but its reply may **inject new steps into the running process** — the steps, and
their preconditions, that the worker just decided are needed.

## The `DYNAMIC` step

A `DYNAMIC` step is authored like any other. It has a `topic`, a worker handles it, and it reports
back through the usual reply. What is special is only what the reply may carry:

```json
{
  "id": "plan",
  "type": "DYNAMIC",
  "name": "Plan the work",
  "topic": "work",
  "preconditionStepId": "start"
}
```

Everything the `DYNAMIC` step type shares with `ACTION` — dispatch, timeout, retries, variables —
behaves identically. Only a `DYNAMIC` step may inject; the engine rejects an injection from any
other step type.

## Injecting steps from a worker

The worker replies with the steps to add, using the SDK helper:

```java
@Bean
public Consumer<TaskExecutionRequested> planTopic(StreamOperations streamBridge) {
    return request -> {
        // Decide the sub-flow. Here: two parallel tasks that fan into a join.
        String stepsJson = """
            [
              {"id":"task-a","type":"ACTION","name":"Task A","topic":"work","preconditionStepId":"plan"},
              {"id":"task-b","type":"ACTION","name":"Task B","topic":"work","preconditionStepId":"plan"},
              {"id":"merge","type":"JOIN","name":"Merge","preconditionStepIds":["task-a","task-b"]}
            ]
            """;

        // Inject the steps, then complete the DYNAMIC step — in that order.
        WorkerReply.injectAndComplete(streamBridge, request, stepsJson, List.of());
    };
}
```

`stepsJson` is a JSON array of step objects in exactly the [workflow-definition step
schema](/guides/workflow-definitions/#step-fields) — the same fields you would write in an `.ec`
file. See [Implementing Workers](/guides/workers/) for the reply contract these helpers implement.

- **`WorkerReply.inject(streamBridge, task, stepsJson)`** sends the injection (a `StepsInjected`
  message) and nothing else — for when the `DYNAMIC` step stays live (it is not done yet) while its
  injected children run.
- **`WorkerReply.injectAndComplete(streamBridge, task, stepsJson, variables)`** injects and then
  completes the `DYNAMIC` step — the common "generate the sub-flow, then finish" reply. It injects
  **first** on purpose: the new steps must exist before the `DYNAMIC` step's completion advances the
  process, or the very steps just added would not yet be there for the engine to consider.

Both go through the same synchronous, retry-or-throw path as every other reply, so a broker that
will not accept the injection fails the listener and the task is redelivered rather than the
injection lost.

## Add-only, and you wire the steps yourself

Injection is **add-only**. The injected steps are materialised alongside the ones already in the
process; nothing existing is rewritten or removed. A `DYNAMIC` step grows the graph, it never
edits it.

The worker supplies each injected step **with its own preconditions** — the relations that make it
reachable. There is **no default wiring**: an injected step with no precondition is not attached to
anything and is simply unreachable. That is deliberate. Auto-wiring an orphan step to the entry
point would hide a mistake; leaving it unreachable makes the mistake visible in the graph, which is
where you want to see it.

So an injected step reaches back into the process — its precondition may reference the injecting
`DYNAMIC` step, or any step already in the process — or forward to a sibling in the same batch, or
both. In the example above, `task-a` and `task-b` wait on `plan`, and `merge` waits on both of
them.

## Validation: the whole batch, or nothing

Before anything is added, the engine validates the batch as a unit:

- **Unique ids** — the injected step ids must not repeat among themselves, and must not collide with
  a step already in the process.
- **Resolved references** — every precondition must point at a step that exists in the process or at
  another step in the same batch.
- **No cycles** — the injected edges must not introduce a precondition cycle (a step that,
  transitively, waits on itself would deadlock).
- **Within budget** — see the guards below.

If any check fails, the **whole batch is rejected and the `DYNAMIC` step is failed** with the
reason. Nothing is partially injected — a half-added sub-flow would leave the graph in a shape no
one wrote. The failure surfaces on the process's **Errors** tab, and the normal failure pipeline
(retry, compensation, process status) engages exactly as it would for any other failed step. A
rejected injection is a failed step, not a silently dropped one.

## Runaway guards

Because an injected step may itself be `DYNAMIC` and inject again, injection is **recursive** — and
recursion needs a bound. Two step-budget guards provide it:

| Guard | Where | Default | Meaning |
| --- | --- | --- | --- |
| `workflow.dynamic.max-steps-per-process` | engine config | `500` | The most step executions any one process may ever hold, injections included |
| `maxSteps` | per definition (`.ec`) | `0` | Overrides the global default for this definition's processes. `0` = fall back to the global default; any positive value takes precedence |

An injection that would push the process past its effective cap — the definition's `maxSteps` when
it declares one, else the global default — is rejected as "step budget exceeded", and the `DYNAMIC`
step fails. Recursion is allowed, but it can never run away: every level of injection counts against
the same budget.

## Injected steps in the diagram

The process diagram shows the process's **actual** step set, not just its definition — so
runtime-injected steps render alongside the declared ones, with their real preconditions. An
injected node is marked distinctly: a **dashed accent border** and a small **⚡ corner badge**, so
you can tell at a glance what the running process grew from what its author wrote.

Each injected step also carries its provenance. `injectedByStepExecutionId` is the id of the
`DYNAMIC` step execution that added it — null for a step created the ordinary way from the
definition. That marker is what the diagram reads to badge the step, and it is also what makes a
re-delivered `StepsInjected` **exactly idempotent**: a `DYNAMIC` step whose injection is delivered
twice finds the children it already created and injects nothing more.

## When to reach for it

A `DYNAMIC` step earns its place when the number or shape of the next steps genuinely depends on
runtime data — a batch split across an unknown number of shards, a plan a rules engine returns, a
set of approvals discovered from the request. When the shape is fixed, author it directly: a
`FORK`/`JOIN` for known parallelism, a `preconditionExpression` for a conditional branch. Dynamic
injection is the tool for the shape you cannot write until the process is running — and, because it
is add-only, validated, bounded and marked in the graph, it stays a graph you can still read.
