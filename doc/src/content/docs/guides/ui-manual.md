---
title: User Interface Manual
description: Complete guide to the EventConductor management UI — workflow engine, forms engine, and console.
---

import { Tabs, TabItem } from '@astrojs/starlight/components';

EventConductor includes a full web management UI built with the [Mateu](https://github.com/miguelperezcolom/mateu) framework. It exposes every operational aspect of the platform: workflow definitions, running processes, form definitions, and pending user tasks.

## Accessing the UI

The UI is served through the API Gateway (default port **8191**). Authentication is handled via **Keycloak** (or any OpenID Connect provider).

Navigate to `http://localhost:8191` in your browser.

![Login page](/screenshots/01-login.png)

Enter your username and password and click **Sign In**.

---

## Console — Main Dashboard

After logging in you land on the **Console** — a top-level management hub that aggregates all installed modules. The top navigation bar provides access to every section: Users, Content, Control Plane, Workflow, Forms, Booking, and the AI agent.

![Console main dashboard](/screenshots/01-dashboard.png)

---

## Workflow Engine

Access the workflow engine by navigating to `http://localhost:8191/_workflow` or clicking **Workflow** in the top navigation bar.

### Dashboard

The workflow dashboard gives you an instant overview of your orchestration platform:

- **Process Definitions** — total number of workflow definitions
- **Running Processes** — currently active process instances
- **Completed Processes** — successfully finished instances
- **Form Definitions** — forms available in the forms engine
- **User Tasks** — pending human tasks across all processes

Two charts provide a visual breakdown: processes by definition (doughnut) and processes by status (bar chart).

![Workflow engine dashboard](/screenshots/02-workflow-home.png)

### Workflow Definitions

Navigate via **Workflow → Definitions**.

The definitions list shows all registered workflow definitions with their ID, name, description, status, and concurrency settings.

![Workflow definitions list](/screenshots/03-workflow-definitions.png)

The page is **read-only**: definitions are authored as JSON/YAML files (classpath, Git
import or database), not created, edited or deleted from the UI.

**Available actions:**

| Button | Description |
|--------|-------------|
| **Import from github** | Pull workflow JSON files from a configured Git repository and upsert them |
| **View** | Open the read-only definition detail |

**View** opens a read-only detail view — not an editor — with a compact property list
(status, version, description, concurrency, cron, max step executions, and a **Paused**
row showing whether the definition's runtime pause flag is set), a **Diagram**
section rendering the workflow graph (read-only, with a full-screen expand button), and
the list of steps below.

Definitions are authored as `.ec` files (edited with the IDE plugins), not in the UI. The detail
view is read-only; its toolbar carries only the runtime toggles plus a YAML export:

| Button | Description |
|--------|-------------|
| **Disable** | Stop accepting new instances (`ACTIVE` → `DISABLED`); running ones continue |
| **Enable** | Resume accepting new instances. Shown while disabled |
| **Pause** | Set the runtime pause flag and pause all the definition's `PENDING`/`RUNNING` processes. New instances (cron included) are still created, born paused. Shown while the definition is not paused |
| **Resume** | Clear the pause flag and resume all the definition's `PAUSED` processes, including the ones born paused. Shown only while the definition is paused |
| **Export YAML** | Download the definition as a YAML file |

Pause/resume and disable/enable are all runtime toggles, orthogonal to each other.

The **Status** column/badge shows the runtime state: **Active**, **Paused**, **Disabled** or **Archived**.

### Processes

Navigate via **Workflow → Processes**.

The processes list shows all process instances with their ID, workflow name, status badge, creation date, start date, and finish date.

![Process instances list](/screenshots/04-processes.png)

**Available actions:**

| Button | Description |
|--------|-------------|
| **Create** | Manually start a new process instance |
| **Retry from failure** | Pick the selected processes up where they stopped: the steps that failed or were cancelled run again, the ones that succeeded are left alone |
| **Restart from the beginning** | Run the selected processes again from the top, the steps that already succeeded included. Asks for confirmation |
| **Search** | Filter by ID or name |
| **View** | Open the process detail |

Both re-run buttons apply to whatever is ticked, and the engine decides: a process that is not
`ERROR` or `CANCELLED` is left alone and says so in the log, rather than the list guessing which
rows qualify from a page that may already be out of date.

**Status badges:**

| Badge | Meaning |
|-------|---------|
| `Pending (0%)` | Created, not yet started |
| `Running (N%)` | In progress — percentage shows completion |
| `Paused` | Held by an operator or a paused definition — no new steps start until resumed |
| `Completed` | All steps finished successfully |
| `Error` | A step failed after exhausting retries |
| `Cancelled` | Process was cancelled |

Clicking **View** on a row opens the process detail, showing all step executions, their individual statuses, variables, and the full audit log. The detail is organised in tabs: **Steps**, **Messages**, **Errors** and **Resources**.

The process detail toolbar carries the process-level actions, each shown only when it
applies to the current status:

| Button | Description |
|--------|-------------|
| **Cancel process** | Cancel the process (hidden once it has finished — completed, error or cancelled) |
| **Pause process** | Pause the process: in-flight work finishes and is accepted, but no successor starts and timer/timeout clocks freeze. Shown while the process is `PENDING` or `RUNNING` |
| **Resume process** | Put the process back to `RUNNING`; frozen clocks are shifted by the pause duration and held successors start. Shown only while the process is `PAUSED` |
| **Retry from failure** | Pick the process up where it stopped: the steps that failed (or were cancelled) run again, the ones that succeeded are left alone. Shown while the process is `ERROR` or `CANCELLED` |
| **Restart from the beginning** | Run the whole process again from the top, the steps that already succeeded included. Same statuses, and it asks first |

#### Which of the two to use

A **retry from failure** is the right one when the failure was the surroundings: a worker that was
down, a downstream service that has since recovered. It is also the cheaper one — the work that
already succeeded is not done twice.

A **restart from the beginning** is right when the run itself was wrong, or when a step that
succeeded produced something the next run needs to produce again. It re-runs every step, so the
workers behind them have to be idempotent — which they had to be anyway, since at-least-once
delivery has always been the contract.

Both re-run the same process instance, keeping its id and business key, and both re-run the
workflow definition **as it was when that process was created** — the definition is snapshotted
into the process at birth, so a restart is not affected by later edits. A restart also puts the
process variables back to the ones it was created with, since re-running from variables a later
step wrote would not be starting from the beginning.

Neither is offered for a `COMPLETED` or `COMPENSATED` process. Both are terminal by design: a
compensated process already rolled back, and running it again is a new process, not a repeat of
this one.

### Analytics

Navigate via **Workflow → Analytics**.

Built-in process analytics over the last 30 days (see the [Process Analytics guide](/guides/analytics/)): KPI cards, a processes-per-day chart, a per-definition table with instance counts, completion/error rates and average/p95 durations, and a per-step table with the slowest step of each definition flagged as the bottleneck (⚠).

![Analytics page](/screenshots/11-analytics.png)

---

## Forms Engine

Access the forms engine by navigating to `http://localhost:8191/_forms` or clicking **Forms** in the top navigation bar.

### Dashboard

The forms engine welcome page confirms the service is running and provides access to all form management sections via the **Forms** menu.

![Forms engine dashboard](/screenshots/06-forms-home.png)

### Form Definitions

Navigate via **Forms → Forms**.

The form definitions list shows all registered forms with their ID, name, and description. From here you can create new forms, edit existing ones with the visual drag-and-drop editor, or delete forms.

![Form definitions list](/screenshots/07-forms-list.png)

**Available actions:**

| Button | Description |
|--------|-------------|
| **New** | Create a new form definition |
| **Delete** | Delete selected form definitions |
| **Search** | Filter forms by name or ID |

### Form Executions

Navigate via **Forms → Executions**.

Form executions are instances of a form linked to a specific process and step — created whenever a `USER_TASK` step is reached in a workflow. This view shows all executions with their linked process, step, status, assigned user, and user group.

![Form executions list](/screenshots/08-form-executions.png)

**Available actions:**

| Button | Description |
|--------|-------------|
| **Claim** | Assign a pending task to the current user |
| **New** | Create a form execution manually |
| **Delete** | Delete selected executions |

**Execution statuses:**

| Status | Meaning |
|--------|---------|
| `Assigned` | Waiting for a user to fill and submit the form |
| `Completed` | User has submitted the form |

### Tasks

Navigate via **Forms → Tasks**.

The Tasks view shows pending user tasks in a simplified view, grouped by form and assignee. Use the **Claim** button to assign an unassigned task to yourself, then click **Run** to open and submit the form.

![User tasks list](/screenshots/09-tasks.png)

Columns shown: ID, Name, Form, Assigned to, Status, and a **Run** action button to open the form directly.

---

## Rule Engine

Deployments that bundle the `rule-engine` module (for example `dev-app` or
`rule-standalone-app`) also serve a rules UI at the `/_rules` path, alongside
`/_workflow` and `/_forms`.

### Rules

Navigate via **Rules → Rules**.

The rules list shows every rule definition in the catalog with its ID, name, type
(expression rule or decision table) and version. Selecting a rule opens it for editing:
the metadata fields are read-only and the full JSON or YAML definition is edited in a
text area — the definition is the source of truth and is validated on save. New rules
can be created and existing ones deleted from the same page.

---

## Navigation structure

```
http://localhost:8191/                   Console (main dashboard)
http://localhost:8191/_workflow          Workflow Engine
  └─ Workflow → Definitions             Workflow definitions list
  └─ Workflow → Processes               Process instances list
       └─ View                          Process detail + step executions
  └─ Workflow → Analytics               Per-definition analytics & bottlenecks

http://localhost:8191/_forms             Forms Engine
  └─ Forms → Forms                      Form definitions list
  └─ Forms → Executions                 Form executions (user tasks)
  └─ Forms → Tasks                      Pending tasks simplified view
  └─ /my-tasks                          Personal task inbox

/_rules                                  Rule Engine (on apps bundling rule-engine,
  └─ Rules → Rules                      e.g. dev-app or rule-standalone-app)
```

---

## Authentication

The UI uses OpenID Connect via Keycloak. The default demo realm is `mateu` hosted on Cloud IAM. To configure your own Keycloak instance, update the Keycloak URL in the frontend bundle configuration and the API Gateway's security settings.

The logged-in user is shown in the top-right corner of the Console. Click your name to access profile options or log out.
