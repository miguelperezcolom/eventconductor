---
title: Starting a Process
description: How to start a workflow process instance — via Kafka or programmatically.
---

A process instance is created by sending a `ProcessCreationRequested` event. Depending on your deployment mode, you can do this via Kafka or programmatically.

To start a process over HTTP and **wait for its answer**, see [Synchronous Invocation](/guides/synchronous-invocation/).

## Via Kafka (mode: kafka)

Send a `ProcessCreationRequested` event to the `upstream` topic:

```json
{
  "type": "process-creation-requested",
  "workflowDefinitionId": "my-workflow",
  "businessKey": "order-123",
  "variables": [
    { "name": "orderId", "value": "123" },
    { "name": "amount",  "value": "99.90" }
  ]
}
```

The `businessKey` is an optional human-readable identifier (e.g. an order number) that lets you look up the process later.

## Programmatically (any mode)

Inject `ProcessUpstreamEventUseCase` and call it directly:

```java
@Autowired
ProcessUpstreamEventUseCase processUpstreamEventUseCase;

public void startOrder(String orderId, String amount) {
    processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(
        new ProcessCreationRequested(
            "my-workflow",
            "order-" + orderId,
            List.of(
                new Variable("orderId", orderId),
                new Variable("amount", amount)
            )
        )
    ));
}
```

## On a schedule (cron)

Declare a `cronExpression` on the workflow definition and the engine creates a process instance at each occurrence, with no external trigger:

```json
{
  "id": "daily-checkin-reminders",
  "name": "Daily check-in reminders",
  "cronExpression": "0 0 9 * * *",
  "steps": [ ... ]
}
```

The expression uses Spring cron syntax (six fields, seconds first — `0 0 9 * * MON-FRI` is 09:00 on weekdays). Only `ACTIVE`, non-draft definitions are scheduled. Each occurrence gets a deterministic business key derived from the definition id and the occurrence time, so redeliveries and multiple orchestrator pods never create duplicate instances. Occurrences that pass while no node is running are skipped, not replayed.

### Schedules with parameters

`cronExpression` is the shorthand for one schedule without variables. For several schedules, a zone of their own, or initial variables, use `schedules` (both may coexist):

```yaml
schedules:
  - id: daily-report-emea        # optional: the schedule's name, one schedule per id
    cron: "0 0 9 * * MON-FRI"
    zone: Europe/Madrid          # IANA zone; default workflow.time.zone (the JVM's zone unless set)
    variables:
      region: EMEA               # initial process variables (strings; numbers/booleans read as text)
  - cron: "0 0 9 * * MON-FRI"
    zone: America/New_York
    variables: { region: AMER }
```

Or put them in a `cron.yaml` next to the definitions — `classpath:/workflows/cron.yaml`, an imported directory or git repository — naming the workflow each entry starts (a bare list, or the list under a single `schedules:` key):

```yaml
- id: daily-report-latam
  cron: "0 0 18 * * MON-FRI"
  workflow: daily-report
  zone: Europe/Madrid
  variables: { region: LATAM }
```

`cron.yaml` is never imported as a definition. Each import of a source replaces that source's entries (a removed entry stops firing); an entry naming an unknown workflow, or with an invalid cron or zone, is logged and skipped. Business keys stay deterministic and distinct per schedule: `<workflow>-cron-<id>-<time with offset>` for a schedule that declares an `id`, wherever it is declared; otherwise `<workflow>-cron-<local time>` for `cronExpression` (as before), `<workflow>-cron-s<index>-<time with offset>` for `schedules[index]`, `<workflow>-cron-f<hash>-<time with offset>` for a `cron.yaml` entry. The definition page's **Schedules** tab lists them all with their next start and status, each id a link to the schedule's own page.

An `id` (letters, digits, `.`, `_`, `-`) names a schedule across the engine: one schedule per id. Without one the engine derives it from the workflow and the entry's content (`<workflow>~cron`, `<workflow>~s<hash>`, `<workflow>~f<hash>`), which is stable across imports but changes when the entry is edited.

### Managing schedules from the UI, the REST API and MCP

A file is the optional, durable home of a schedule, not a precondition for having one. **Workflow → Schedules** in the admin console lists every schedule — workflow, cron and the same in words, zone, next start, source (`Definition`, `cron.yaml (git)`, `cron.yaml (directory)`, `cron.yaml (classpath)`, `UI/API`) and status — and creates new ones. The same is available as a REST API under `/workflow/api/schedules` and as MCP tools (`listSchedules`, `getSchedule`, `createSchedule`, `updateSchedule`, `deleteSchedule`, `pauseSchedule`, `resumeSchedule`, `runScheduleNow`, `exportScheduleYaml`, `listWorkflowDefinitions`).

- **Any schedule** can be paused and resumed — the pause is kept by id, so it survives every re-import of the file that declares it; resuming does not replay what fell due meanwhile — and **run now**, which starts one process immediately (paused or not) under the business key `<workflow>-cron-manual-<id>-<instant>`.
- **Only schedules created from the UI/API/MCP** are edited and deleted there. One declared in a definition or a `cron.yaml` is changed in its file.

The lifecycle:

1. **Create** the schedule from the UI (or the API, or an agent) with an `id`, and try it: run it now, pause it, edit it.
2. **Promote** it: its page shows its `cron.yaml` entry, id included (also `GET /workflow/api/schedules/{id}/yaml`, `exportScheduleYaml`). Commit it next to the definitions.
3. **Git owns it**: on the next import, what comes from git overwrites what was set from the UI, matched by `id` — the stored copy is deleted, the file's values and source take over, its pause carries over, and its business keys do not change, so no occurrence fires twice. An `id` declared in a definition's `schedules` takes over the same way.
4. **Gone from git, gone**: an entry removed from its file is deleted with its pause, including one that started life in the UI. Imports never touch the schedules created from the UI/API/MCP otherwise.

Cron scanning can be tuned or disabled with `workflow.cron-scan-interval-ms` and `workflow.cron-enabled` (see the [configuration reference](/reference/configuration/)).

## On a domain event

A definition can declare `triggers`: a CloudEvent of a given type arriving on a destination (a topic in kafka mode; the REST/MCP inject or the embedded API otherwise) starts a process, with a business key and initial variables mapped from the event — at most once per event and trigger. The same trigger, naming the workflow it starts, can live in an `events.yaml` next to the definitions, as a schedule can live in `cron.yaml` — or be created from **Workflow → Event triggers**, `POST /workflow/api/triggers` or the MCP tool `createEventTrigger`, and promoted to `events.yaml` later.

```yaml
triggers:
  - destination: bookings
    type: com.acme.booking.created
    businessKey: "booking-${data.bookingId}"
    variables: { bookingId: "${data.bookingId}" }
```

```yaml
# events.yaml, next to the definitions
- destination: customers-cdc
  type: "*"
  workflow: sync-customer
  filter: "data.op == 'u'"
  variables: { customerId: "${data.after.id}" }
```

See [Starting on and waiting for domain events](/guides/domain-event-triggers/).

## Process variables

Variables are key-value pairs attached to the process. They are:

- Passed at creation time
- Merged with output variables reported by each worker
- Available to JEXL precondition expressions in step definitions
- Included in the process state returned by the MCP `getProcessDetails` tool

Variables are typed as strings. Numeric comparisons in JEXL expressions work on the string representation.

One variable name is read by something other than your own workers: `TEST_CONFIG`. The [test worker](/guides/test-worker/) takes from it the scenario it should play back — how long each task takes, what it reports, which one fails and which one never answers — so a process started with it drives a whole definition through an outcome without a worker having been written for it. It is an ordinary string variable, which means the JSON travels inside it escaped.

## Looking up a process

Use `ProcessRepository` to query by ID or business key (both return an `Optional`):

```java
@Autowired
ProcessRepository processRepository;

// By ID
Optional<Process> process = processRepository.findById(processId);

// By business key
Optional<Process> process = processRepository.findByBusinessKey("order-123");
```

## Resuming a waiting process (WAIT_FOR_MESSAGE steps)

A process paused on a `WAIT_FOR_MESSAGE` step resumes when a matching message arrives — via `POST /workflow/api/messages`, the `sendMessage` MCP tool, a raw `message-received` event on the Kafka `upstream` topic, or a `SEND_MESSAGE` step in another process (in-engine process-to-process signaling, no worker needed). See [Step Types — WAIT_FOR_MESSAGE](/reference/step-types/#wait_for_message) for correlation and delivery semantics.

## Pausing and resuming

### A single process

Call `PauseProcessUseCase` with the process id to pause a `PENDING` or `RUNNING` process, and `ResumeProcessUseCase` to put a `PAUSED` process back to `RUNNING`:

```java
@Autowired
PauseProcessUseCase pauseProcessUseCase;
@Autowired
ResumeProcessUseCase resumeProcessUseCase;

pauseProcessUseCase.handle(new PauseProcessCommand(processId));
resumeProcessUseCase.handle(new ResumeProcessCommand(processId));
```

The same operations are available as the **Pause** / **Resume** toolbar actions on the process detail in the UI, and as the `pauseProcess` / `resumeProcess` MCP tools.

Pause freezes the frontier, not in-flight work:

- **Nothing is cancelled.** Workers that are already running finish and their reports are accepted — steps complete and their output variables merge into the process. Messages still correlate and complete `WAIT_FOR_MESSAGE` steps. Only the successors are held: no new step starts until the process is resumed.
- **Clocks freeze.** Timeout and TIMER scanning skip paused processes, and on resume every non-terminal started step's `startedAt` is shifted forward by the pause duration — so a step timeout or a TIMER due-moment resumes where it left off instead of firing the instant you resume. Blocking-error handling is deferred the same way.
- Cancelling a `PAUSED` process works as usual.

Pausing a process that is not `PENDING`/`RUNNING` (or resuming one that is not `PAUSED`) is a no-op.

### A whole workflow definition

`PauseWorkflowUseCase` pauses a definition in one shot: it sets the definition's runtime `paused` flag and pauses all its `PENDING`/`RUNNING` processes. `ResumeWorkflowUseCase` clears the flag and resumes all its `PAUSED` processes:

```java
@Autowired
PauseWorkflowUseCase pauseWorkflowUseCase;
@Autowired
ResumeWorkflowUseCase resumeWorkflowUseCase;

pauseWorkflowUseCase.handle(workflowDefinitionId);
resumeWorkflowUseCase.handle(workflowDefinitionId);
```

Also available as **Pause** / **Resume** on the definition detail in the UI and as the `pauseWorkflow` / `resumeWorkflow` MCP tools.

While a definition is paused, **new instances are still accepted — they are created born-`PAUSED`**: the process and its steps exist, but nothing runs until the definition is resumed. This includes cron: a paused definition keeps creating an instance at each occurrence, born paused. Inputs are never rejected — pausing a definition parks the work, it does not drop it. The `paused` flag is a runtime toggle, orthogonal to the runtime `disabled`/`archived` flags.

## Cancelling a process

Call `CancelProcessUseCase` with the process id:

```java
@Autowired
CancelProcessUseCase cancelProcessUseCase;

cancelProcessUseCase.handle(new CancelProcessCommand(processId));
```

Running step executions will be cancelled (workers are notified via `TaskCancellationRequested`), and the process status will transition to `CANCELLED`. Cancelling an already `COMPLETED` or `CANCELLED` process is a no-op.

Note that publishing a `ProcessCancellationRequested` event on the upstream surface has **no effect** — no handler consumes it.

A process in `ERROR` can be retried with `RetryProcessUseCase`, which resets its failed step executions to `CREATED` and puts the process back to `RUNNING`:

```java
retryProcessUseCase.handle(new RetryProcessCommand(processId));
```
