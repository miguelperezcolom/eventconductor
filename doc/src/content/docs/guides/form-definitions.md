---
title: Form Definitions
description: Defining forms for user tasks in the EventConductor forms engine. Supports JSON and YAML.
---

The forms engine manages form definitions and form executions. Forms can be written in **JSON** or **YAML** (`.json`, `.yaml`, `.yml`), stored in version control, and referenced by `USER_TASK` steps in workflow definitions. They can be imported from Git at startup, on demand via the MCP tool `importFormsFromGit`, or automatically via a **GitHub webhook**.

## Form definition format

Both formats are fully equivalent.

**JSON:**

```json
{
  "id": "expense-approval-form",
  "name": "Expense Approval",
  "description": "Approve or reject an expense claim",
  "fields": [
    {
      "id": "decision",
      "name": "Decision",
      "type": "SELECT",
      "required": true,
      "options": ["APPROVE", "REJECT"]
    },
    {
      "id": "comments",
      "name": "Comments",
      "type": "TEXTAREA",
      "required": false
    }
  ]
}
```

**YAML:**

```yaml
id: expense-approval-form
name: Expense Approval
description: Approve or reject an expense claim
fields:
  - id: decision
    name: Decision
    type: SELECT
    required: true
    options:
      - APPROVE
      - REJECT

  - id: comments
    name: Comments
    type: TEXTAREA
    required: false
```

### Top-level fields

| Field | Type | Description |
|---|---|---|
| `id` | string | Unique form identifier (referenced by `USER_TASK` steps) |
| `name` | string | Human-readable form name |
| `description` | string | Optional description shown to the user |
| `fields` | array | List of form fields |

### Field types

| Type | Description |
|---|---|
| `TEXT` | Single-line text input |
| `TEXTAREA` | Multi-line text input |
| `NUMBER` | Numeric input |
| `SELECT` | Dropdown selection |
| `CHECKBOX` | Boolean checkbox |
| `DATE` | Date picker |
| `EMAIL` | Email input with validation |

### Field fields

| Field | Type | Description |
|---|---|---|
| `id` | string | Unique identifier within the form |
| `name` | string | Label displayed to the user |
| `type` | enum | Field type (see above) |
| `required` | boolean | Whether the field is mandatory |
| `options` | array | Options for SELECT fields |
| `defaultValue` | string | Default value |
| `placeholder` | string | Placeholder text |

## Loading form definitions

### In-memory mode

Place form definition files (`.json`, `.yaml`, or `.yml`) under `src/main/resources/forms/`. They are loaded at startup.

### JPA mode

Import from Git using the MCP tool `importFormsFromGit`:

```
"Import the latest form definitions from Git"
```

Or trigger it programmatically via the forms engine API.

## Importing from Git

The forms engine can clone one or more Git repositories at startup and import every `.json` / `.yaml` / `.yml` file that contains a valid form definition (i.e. has both `name` and `fields` fields).

### Configuration

```yaml
forms:
  git-import:
    repositories:
      - url: https://github.com/your-org/form-defs.git
        branch: main          # optional, defaults to "main"
        username: my-user     # optional — for HTTPS with token auth
        password: ghp_xxx     # optional — personal access token
```

Multiple repositories are supported. Each is cloned into a temporary directory, scanned recursively, and deleted immediately after import.

### Startup import

Repositories are imported automatically on startup by `FormGitImportRunner`. If a definition with the same ID already exists it is overwritten (upsert). Definitions without an `id` get one assigned automatically.

### GitHub webhook

To re-import form definitions automatically after a push or merge, configure the forms engine as a GitHub webhook receiver.

**`application.yml`:**

```yaml
forms:
  git-import:
    webhook-secret: mysecret   # optional — same value you set in GitHub repo settings
    repositories:
      - url: https://github.com/your-org/form-defs.git
        branch: main
```

**GitHub setup:** in your definitions repository go to *Settings → Webhooks → Add webhook* and fill in:

| Field | Value |
|---|---|
| Payload URL | `https://your-server/forms/webhooks/github` |
| Content type | `application/json` |
| Secret | same value as `forms.git-import.webhook-secret` |
| Events | *Just the push event* |

**Behaviour:**

- The endpoint responds **202 Accepted** immediately so GitHub's 10-second delivery timeout is never hit.
- The import runs in the background; progress is logged at `INFO` level.
- If `webhook-secret` is set, the `X-Hub-Signature-256` header is verified using HMAC-SHA256. Requests with a missing or invalid signature are rejected with `401 Unauthorized`.
- If `webhook-secret` is blank, any caller can trigger an import (suitable for internal networks only).

## Visual form editor

The platform UI includes a **drag-and-drop form editor** for building form layouts visually. Changes are persisted back as JSON definitions. Access it from the management UI under Form Definitions.
