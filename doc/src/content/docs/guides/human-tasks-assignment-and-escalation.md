---
title: "Human tasks: assignment and escalation"
description: Who a USER_TASK is for — assignee, candidate users and groups, priority, due date — rendered from the process; what happens while it waits — reassign, notify, escalate to another step; the task inbox in the UI, REST and MCP; lifecycle events and notifications.
---

A [`USER_TASK`](/guides/user-tasks/) waits for a person. Since 2.31 the definition says **which** person — or which people — and **what happens if nobody acts**, the way BPMN user tasks do (assignee, candidate users and groups, due and follow-up dates, priority, escalation timers), written in the DSL:

```yaml
- id: approve
  type: USER_TASK
  name: Approve the expense
  formId: expense-approval
  preconditionStepId: register
  timeout: P10D                              # the hard limit, as before (optional)
  assignment:
    assignee: "${manager}"                   # one person — the task is ASSIGNED to them
    candidateUsers: [alice, "${backup}"]     # people who may claim it
    candidateGroups: ["finance-${region}"]   # groups whose members may claim it
    priority: "${amount > 1000 ? 90 : 40}"   # 0–100, default 50
    dueDate: P2D                             # a duration from creation, a date, or a template
    followUpDate: P1D
  escalation:
    - after: PT48H                           # still open 48 hours after it was created…
      action: reassign                       # …it is for the finance managers now
      to:
        candidateGroups: [finance-managers]
      publishEvent:                          # …and somebody is told
        destination: notifications
        type: com.acme.task.escalated
        payload:
          task: "${step.id}"
          expense: "${expenseId}"
          level: "${escalation.level}"
    - after: P4D
      action: notify                         # recorded and announced; the task is unchanged
    - after: P5D
      escalateTo: director-review            # the approval is closed; the process goes on there
- id: director-review
  type: USER_TASK
  name: Director review
  formId: expense-approval
  preconditions:
    - stepId: approve
      expression: "false"                    # reached only through approve's escalateTo
  assignment:
    candidateGroups: [directors]
    priority: 100
```

## Assignment

Every field of `assignment` is optional and may be a `${…}` [template](/guides/payload-templates/) over the process variables — in a [`forEach`](/guides/loops-and-multi-instance/), over the iteration's item too (`"${line.owner}"`). The engine renders it **when the task is created**, in the same JEXL sandbox as every other template, and the forms engine stores the result: plain names, a number, two instants. Like the variables a task shows, it is a snapshot — a variable changed later does not move the task; an escalation does.

| Field | Renders to | Meaning |
|---|---|---|
| `assignee` | a user name | The task is created **ASSIGNED** to them. Empty (an undefined variable) is unassigned. |
| `candidateUsers` | names | People who may claim it. A list (of names or templates), or one template giving a list — a JSON array variable or a comma-separated text. |
| `candidateGroups` | names | Groups whose members may claim it. Same forms as `candidateUsers`. |
| `priority` | 0–100 | Higher is more urgent. Default 50. Inboxes sort by it. |
| `dueDate` | an instant | An ISO-8601 duration counted from the task's creation (`P2D`, `PT4H`), an ISO date (`2026-12-01`, read at the start of the day) or date-time (without an offset it is read in `workflow.time.zone`), or a template giving either. Past it, an open task is **overdue**. |
| `followUpDate` | an instant | When someone should look at it again — same forms. Informative: shown and returned, nothing fires on it. |

An assignment that cannot be rendered — a template that fails, a priority that is not a number from 0 to 100, a date that is not a date — **fails the step** with the reason on the timeline, as an `HTTP_CALL` whose request cannot be rendered does: a task quietly offered to nobody, or to everybody, is worse than one that visibly did not start. The definition is validated on import and by the [Maven plugin](/reference/maven-plugin/) with the same rules.

A task that declares no `assignment` behaves exactly as before: it names nobody and is open to whoever may work on its form.

### Who sees and claims a task

The forms engine decides, the same way in the UI, the REST API and the MCP tools:

| | Who |
|---|---|
| **sees** it | its holder (the assignee, or whoever claimed it); while nobody holds it, its candidate users and the members of its candidate groups — or **everybody**, when the task names nobody at all; and task admins, always |
| **claims** it | anyone who sees it while nobody holds it. Claiming is atomic: of two people claiming at once, one gets it. The task becomes ASSIGNED to them |
| **completes** it | its holder |
| **releases** it | its holder, or a task admin. It goes back to its candidates, PENDING |
| **reassigns** it | a task admin (`POST /forms/api/tasks/{id}/assign`) |

- **Groups are roles.** A caller's groups are the roles the engine reads for them — `workflow.security.claims.roles` from a forwarded token (default `realm_access.roles,roles`), or the `ROLE_` authorities of a login. To use an identity provider's `groups` claim, add it there: `workflow.security.claims.roles: realm_access.roles,roles,groups`. A leading `/` is ignored, so Keycloak's group path `/finance` matches `finance`.
- **Task admins** hold one of `forms.tasks.admin-roles` (default `task-admin`).
- **The form still decides too.** A form's `requiredRoles`/`requiredScopes` (with `workflow.security.flow-authorization.enabled`) apply on top — both must allow it, an admin included.
- A task that named somebody stays restricted to the people it names now: one assigned to a single person who releases it is visible to task admins only.
- A task somebody else holds is not listed for anybody else (admins excepted), and a link to it says the task is not offered to you — as the REST API answers 404, like a task that does not exist.

## Escalation

Each entry fires **once**, `after` an ISO-8601 duration from the task's creation, while the task is still open. They are listed in the order they fire.

| `action` | What happens |
|---|---|
| `reassign` | The task is for `to` from now on — its assignee and candidates are **replaced** by `to`'s (`assignee`, `candidateUsers`, `candidateGroups`, templates as in `assignment`). An assignee in `to` gets it ASSIGNED; otherwise it is PENDING for the new candidates. Its priority and dates are kept. |
| `notify` | Nothing changes on the task. The escalation is recorded and announced — and its `publishEvent`, if any, is published. |
| `escalateTo` | The task is closed (cancelled in the forms engine) and the step ends `TIMEOUT` with its route set to the step named in `escalateTo` — the process goes on there exactly as a declared [`onTimeoutStepId`](/guides/retries-timeouts-compensation/#on-timeout-routing) takes it, without failing and without compensating. Give the target a precondition on the task that the normal route never satisfies (`expression: "false"`), as for any on-timeout branch. |

Without `action`, it is read from what the entry declares: `escalateTo` if it names a step, `reassign` if it has a `to`, otherwise `notify`.

Any entry may **`publishEvent`** — the shape of a [`PUBLISH_EVENT`](/guides/publishing-events/)'s `event` (destination, type, key, payload…). It is rendered from the process plus `escalation` — `{level, action, after, escalateTo, assignee, candidateUsers, candidateGroups}` (who the task is for once this escalation has applied) — and written to the outbox in the transaction that records the escalation, with the id `<stepExecutionId>-escalation-<level>`, so a consumer deduplicates on it. An event that cannot be rendered or published is logged on the timeline; the escalation still counts as fired.

**How it runs.** Escalations are timers of the engine, not of the forms engine: the next one is folded into the step's materialised deadline, so the same indexed deadline scan that fires timeouts finds it (no extra scan, no new table), and the [test kit](/guides/testing-workflows/)'s `advanceTime` stops at it. When it fires, under the process lock:

1. the count of fired escalations moves on — on the step execution, so a redelivered check, another pod or a restart does not fire it again — and with it the deadline, to the next escalation or the time limit;
2. a line goes on the process timeline (`Escalation 1 of 3 (after PT48H): task reassigned to candidate groups [finance-managers].`), the counter `eventconductor.tasks.escalations` (tags `action`, `workflowDefinitionId`) goes up, and `publishEvent` goes to the outbox;
3. the forms engine is told (`human-task-escalation-requested` on the task's topic): it records the level, applies a reassignment, and announces an `escalated` task event — once per level.

Several escalations due at once (the engine was down, or `advanceTime` jumped past them) fire together, in order.

**Rules** (checked on import and by the Maven plugin):

- only a `USER_TASK` has `assignment` or `escalation`;
- `after` is a positive ISO-8601 duration, and entries are in firing order;
- a `reassign` needs a `to` naming somebody; `to` and `escalateTo` only on their own action;
- one `escalateTo` at most, the last entry, naming another step of the definition — and not on a `forEach` step: each iteration is a task of its own, and one of them cannot route the whole step (reassign and notify work per iteration);
- not together with a [`deadline`](/guides/scheduling-and-deadlines/) — escalations are counted from the task's creation; bound the step with `timeout` instead.

**With `timeout`.** A `timeout` is still the hard limit: if it is reached first — or has already passed when a pending escalation is checked (after downtime, say) — the step times out (`onTimeoutStepId` or failure, retries as usual) and the pending escalations are dropped. **A retry** of the step (a timeout with `retries` left, an operator's retry) creates the task afresh and starts its escalations over. A **paused** process escalates nothing until it is resumed; its escalation moments move with the pause, like its timeouts.

**In a forEach**, every iteration is a task with its own assignment (rendered with its item), its own clock and its own escalations.

## The task inbox

The forms engine lists a person's tasks the same way everywhere, applying the rules above:

- **UI** — **Tasks** (`/forms/tasks`): filters *Show* (All, Mine, Claimable, My groups), *Overdue only*, *Min priority*, *Process*, *Business key*; most urgent first, then soonest due; columns for priority, due date and an *Overdue* / *Escalated* badge, who holds it and who it is offered to. **Tasks v2** shows the same open tasks with their form inline, then the ones you completed. The task page shows priority, due date (overdue), escalation and who it is offered to, and offers *Claim* / *Release* / *Complete* only when they apply. The header badge counts your open tasks and says how many are overdue.
- **REST** — `/forms/api/tasks`, always as the signed-in caller (anonymous is refused with 401):

  | | |
  |---|---|
  | `GET /forms/api/tasks` | `scope=all\|mine\|candidate\|groups`, `overdue=true\|false`, `minPriority=`, `dueBefore=` (ISO instant), `processId=`, `businessKey=`, `formId=`, `status=PENDING,ASSIGNED,…` (default the open ones), `sort=priority\|due\|created`, `page=`, `size=` (max 500) → `{items, total, page, size}` |
  | `GET /forms/api/tasks/{id}` | one task, with its variables and the values submitted; 404 when you may not see it |
  | `POST /forms/api/tasks/{id}/claim` | 409 when somebody holds it |
  | `POST /forms/api/tasks/{id}/release` | |
  | `POST /forms/api/tasks/{id}/complete` | `{"values": {"decision": "APPROVE"}}` — validated against the form (400 when a required field is missing); only by the holder (409) |
  | `POST /forms/api/tasks/{id}/assign` | `{"assignee": "bob", "candidateUsers": [], "candidateGroups": ["finance"]}` — task admins only (403) |

  Each task: `id, formId, processId, businessKey, stepId, status, assignee, candidateUsers, candidateGroups, priority, dueDate, followUpDate, createdAt, overdue, escalationLevel, escalatedAt` (moments as ISO instants, UTC). A form's requirements refused answer 403.
- **MCP** — `searchTasks(scope, overdueOnly, minPriority, processId, businessKey)` answers as the caller, most urgent first.

Statuses: **PENDING** (nobody holds it), **ASSIGNED** (somebody does — its assignee, or whoever claimed it), **COMPLETED**, **CANCELLED**.

## Lifecycle events and notifications

Every change of a task is announced by the forms engine on its `humanTasks` binding (topic `human-tasks` in the standalone app) as a **binary CloudEvent**: `ce_type` is `io.eventconductor.task.<event>` — `created`, `assigned`, `claimed`, `released`, `completed`, `cancelled`, `escalated` — and the record is the `HumanTaskChanged`:

```json
{"taskId": "…", "formId": "expense-approval", "formName": "Expense approval", "processId": "…",
 "stepId": "approve", "status": "ASSIGNED", "event": "escalated", "userId": null,
 "candidateUsers": [], "candidateGroups": ["finance-managers"], "priority": 40,
 "dueDate": "2026-10-21T08:00:00Z", "escalationLevel": 1, "businessKey": "expense-7",
 "requiredRoles": [], "at": "2026-10-21T08:00:00Z"}
```

They are also counted: `eventconductor.tasks.events` (tags `event`, `formId`); `eventconductor.forms.task.duration` now measures from the task's creation.

### A notification pattern

Two ways to tell people, both without writing a worker:

**1. From the definition — `publishEvent` on an escalation.** The event goes to a [destination](/guides/publishing-events/#destinations-are-configuration) (`workflow.events.destinations.notifications.topic`) where a notification service listens, as in the example at the top. Use it for escalations that the process itself knows how to describe.

**2. From the task's lifecycle — an event trigger on `human-tasks`.** Map a destination to the forms engine's topic and start a small notification workflow on the events you care about — "you've been assigned a task", "a task in your group is overdue and escalated":

```yaml
# application.yaml of the orchestrator
workflow:
  events:
    destinations:
      human-tasks: { topic: human-tasks }
      chat: { topic: chat-messages }
```

```yaml
# events.yaml next to the definitions
- id: on-task-assigned
  destination: human-tasks
  type: io.eventconductor.task.assigned
  workflow: notify-task-assignee
  filter: "data.userId != null"
  businessKey: "notify-${data.taskId}-${data.userId}"     # one notification per task and person
  variables:
    taskId: "${data.taskId}"
    assignee: "${data.userId}"
    form: "${data.formName}"
    due: "${data.dueDate}"
- id: on-task-escalated
  destination: human-tasks
  type: io.eventconductor.task.escalated
  workflow: notify-task-escalated
  businessKey: "escalated-${data.taskId}-${data.escalationLevel}"
  variables:
    taskId: "${data.taskId}"
    groups: "${data.candidateGroups}"
```

```yaml
# notify-task-assignee.yaml
id: notify-task-assignee
name: Tell the assignee
steps:
  - id: start
    type: START
    name: Start
  - id: tell
    type: PUBLISH_EVENT              # or an HTTP_CALL to your chat or mail API
    name: Tell the assignee
    preconditionStepId: start
    event:
      destination: chat
      type: com.acme.chat.direct-message
      payload:
        to: "${assignee}"
        text: "You have a task: ${form} (due ${due}) — /forms/task/${taskId}"
  - id: end
    type: END
    name: End
    preconditionStepId: tell
```

The business key makes each notification happen once however often the event is delivered ([each event is used once](/guides/domain-event-triggers/#each-event-is-used-once)).

## Testing

With the [test kit](/guides/testing-workflows/) the task is parked like any USER_TASK, its rendered assignment is in the invocation's `__task` variable, and `advanceTime` fires the escalations at their moments:

```java
var expense = runtime.start("expense-approval", "expense-1",
        Map.of("manager", "bob", "region", "emea", "amount", 1500));

var assignment = HumanTaskAssignment.fromJson(
        runtime.invocationsOf("approve").getFirst().variable(HumanTaskAssignment.VARIABLE));
assertThat(assignment.assignee()).isEqualTo("bob");
assertThat(assignment.priority()).isEqualTo(90);

runtime.advanceTime(Duration.ofHours(48));          // reassigned
var approve = expense.stepExecutions("approve").getFirst();
assertThat(approve.escalationsFired()).isEqualTo(1);
assertThat(approve.assignment().candidateGroups()).containsExactly("finance-managers");
assertThat(expense).hasPublishedEvent("com.acme.task.escalated");

runtime.advanceTime(Duration.ofDays(3));            // escalated to the director
assertThat(expense).hasStepStatus("approve", StepExecutionStatus.TIMEOUT).isWaitingAt("director-review");
```

## Storage

The forms engine keeps the assignment on the task (migration `V8__task_assignment.sql` of its own schema, `flyway_schema_history_forms`): `priority`, `due_date`, `follow_up_date`, `business_key`, `created_at`, `escalation_level`, `escalated_at`, `restricted` on `form_execution_entity`, and one row per candidate in `form_execution_candidate_user` / `form_execution_candidate_group`, indexed by name so "offered to me or my groups" is an index lookup. Moments are UTC. Tasks from before keep `NULL`s, read as priority 50, no due date, not restricted. The engine needs no migration: the rendered assignment and the count of fired escalations travel with the step execution.
