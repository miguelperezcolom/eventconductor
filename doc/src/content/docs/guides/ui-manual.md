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

**Available actions:**

| Button | Description |
|--------|-------------|
| **Import from github** | Pull workflow JSON files from a configured Git repository and upsert them |
| **New** | Create a new workflow definition manually |
| **Delete** | Delete selected definitions |
| **View** | Open the definition detail and visual editor |

**Definition statuses** displayed in the Status column:

| Status | Meaning |
|--------|---------|
| `ACTIVE` | Accepts new process instances |
| `DRAFT` | Under construction, not executable |
| `DISABLED` | No new instances; running ones continue |
| `ARCHIVED` | Retired |

### Processes

Navigate via **Workflow → Processes**.

The processes list shows all process instances with their ID, workflow name, status badge, creation date, start date, and finish date.

![Process instances list](/screenshots/04-processes.png)

**Available actions:**

| Button | Description |
|--------|-------------|
| **Create** | Manually start a new process instance |
| **Retry** | Re-trigger all selected ERROR processes |
| **Search** | Filter by ID or name |
| **View** | Open the process detail |

**Status badges:**

| Badge | Meaning |
|-------|---------|
| `Pending (0%)` | Created, not yet started |
| `Running (N%)` | In progress — percentage shows completion |
| `Completed` | All steps finished successfully |
| `Error` | A step failed after exhausting retries |
| `Cancelled` | Process was cancelled |

Clicking **View** on a row opens the process detail, showing all step executions, their individual statuses, variables, and the full audit log.

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

## Navigation structure

```
http://localhost:8191/                   Console (main dashboard)
http://localhost:8191/_workflow          Workflow Engine
  └─ Workflow → Definitions             Workflow definitions list
  └─ Workflow → Processes               Process instances list
       └─ View                          Process detail + step executions

http://localhost:8191/_forms             Forms Engine
  └─ Forms → Forms                      Form definitions list
  └─ Forms → Executions                 Form executions (user tasks)
  └─ Forms → Tasks                      Pending tasks simplified view
  └─ /my-tasks                          Personal task inbox
```

---

## Authentication

The UI uses OpenID Connect via Keycloak. The default demo realm is `mateu` hosted on Cloud IAM. To configure your own Keycloak instance, update the Keycloak URL in the frontend bundle configuration and the API Gateway's security settings.

The logged-in user is shown in the top-right corner of the Console. Click your name to access profile options or log out.
