---
title: Form Definitions
description: Defining forms for user tasks in the EventConductor forms engine. Supports JSON and YAML.
---

The forms engine manages form definitions and form executions. Forms can be written in **JSON** or **YAML** (`.json`, `.yaml`, `.yml`), stored in version control, and referenced by `USER_TASK` steps in workflow definitions.

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

## Visual form editor

The platform UI includes a **drag-and-drop form editor** for building form layouts visually. Changes are persisted back as JSON definitions. Access it from the management UI under Form Definitions.
