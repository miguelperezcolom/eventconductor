---
title: Form Definitions
description: Defining forms for user tasks in the EventConductor forms engine. Supports JSON and YAML.
---

The forms engine manages form definitions and form executions. Forms can be written in **JSON** or **YAML** (`.json`, `.yaml`, `.yml`, or `.ecform`, which the visual editor and the IDE plugins write), stored in version control, and referenced by `USER_TASK` steps in workflow definitions. They can be imported from Git at startup, on demand via the MCP tool `importFormsFromGit`, or automatically via a **GitHub webhook**.

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
      "label": "Decision",
      "dataType": "string",
      "stereotype": "radio",
      "required": true,
      "options": [
        { "value": "APPROVE", "label": "Approve the claim" },
        { "value": "REJECT", "label": "Reject the claim" }
      ]
    },
    {
      "id": "comments",
      "label": "Comments",
      "dataType": "string",
      "stereotype": "textarea"
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
    label: Decision
    dataType: string
    stereotype: radio
    required: true
    options:
      - value: APPROVE
        label: Approve the claim
      - value: REJECT
        label: Reject the claim

  - id: comments
    label: Comments
    dataType: string
    stereotype: textarea
```

### Top-level fields

| Field | Type | Description |
|---|---|---|
| `id` | string | Unique form identifier (referenced by `USER_TASK` steps). Optional: a file that omits it is imported under an id derived from its path relative to the scan root — `checkin/walk.ecform` becomes `checkin.walk` — so re-importing the file updates the form it created rather than adding another. A form referenced by a `USER_TASK` step is better off declaring one, since moving the file would otherwise change what the step points at |
| `name` | string | Human-readable form name |
| `description` | string | Optional description of the form |
| `fields` | array | The fields, in the order they are shown. At least one |

### Field properties

| Field | Type | Default | Description |
|---|---|---|---|
| `id` | string | — | Identifier, unique **within this form**. It is the name of the process variable the answer lands in |
| `label` | string | — | Label shown to the user |
| `dataType` | enum | — | What the value *is*: `string`, `integer`, `number`, `bool`, `date`, `time`, `dateTime`, `dateRange`, `money`, `array`, `file`, `status`, `component`, `menu`, `range`, `action`, `actionGroup` |
| `stereotype` | enum | `regular` | How it is *rendered*: `regular`, `radio`, `checkbox`, `textarea`, `toggle`, `combobox`, `select`, `email`, `password`, `richText`, `listBox`, `html`, `markdown`, `image`, `icon`, `link`, `money`, `grid`, `color`, `choice`, `popover`, `slider`, `button`, `stars` |
| `required` | boolean | `false` | Whether the user must answer before submitting |
| `description` | string | — | Hint or help text shown below the field |
| `options` | array | — | The choices offered, for the stereotypes that pick from a list (see below) |
| `optionsSource` | object | — | Where to fetch the choices from instead: a REST endpoint called as the form renders (see below). Mutually exclusive with `options` |

Two properties, not one, decide a field: `dataType` is what the value is, `stereotype` is how it is
asked for. A yes/no answer is `dataType: bool` whether it is drawn as a checkbox or as a toggle.

### Choices: `options`

A field with `stereotype: radio`, `select`, `combobox`, `listBox` or `choice` picks from a fixed
list, and `options` is that list, in the order it is shown. Each entry is a **value/label pair**:

```yaml
options:
  - value: WALK
    label: Walk the guest to another hotel
  - value: REFUND
    label: Refund the reservation
  - value: REJECT          # label omitted → the user sees "REJECT"
```

| Property | Type | Description |
|---|---|---|
| `value` | string | What the form submits, and what the process variable ends up holding. Unique within the field |
| `label` | string | What the user reads. Defaults to the value when omitted |

Keeping the two apart is the point: the definition can say `REFUND` to the engine and "Refund the
reservation" to the person filling the form, so the workflow's guards
(`preconditions[].expression`) stay written against stable codes while the wording changes freely.

A field that declares no options takes free input; declaring them on a stereotype that does not
pick from a list is accepted and ignored.

:::caution[Quote `YES` and `NO` in YAML]
YAML 1.1 reads bare `YES`, `NO`, `ON`, `OFF`, `Y` and `N` as booleans, so `value: YES` reaches the
engine as the string `true`. Quote any option value that looks like one — `value: "YES"` — or use
JSON, where the question does not arise.
:::

### Choices from a REST endpoint: `optionsSource`

A list written into the definition says what the choices were when the form was authored. When they
are a catalogue, a directory or a price list, what you want is what they are **now**, and
`optionsSource` says where to get them: a REST endpoint the **browser** calls as the form renders.

```yaml
- id: country
  label: Country
  dataType: string
  stereotype: select
  optionsSource:
    url: https://restcountries.com/v3.1/all?fields=cca2,name
    itemsPath: ""              # the response root is already the array
    valuePath: cca2
    labelPath: name.common     # dot paths navigate nested JSON
```

| Property | Default | Meaning |
|---|---|---|
| `url` | — | The endpoint. Supports `${state.x}` interpolation against the form's own values |
| `method` | `GET` | HTTP method |
| `headers` | `{}` | Request headers, values interpolated |
| `body` | `""` | Request body template, interpolated, for non-`GET` methods |
| `itemsPath` | `""` | Dot path to the array inside the response (`data.countries`); blank means the response root **is** the array |
| `valuePath` | `value` | Dot path within each item to the option value |
| `labelPath` | `label` | Dot path within each item to the option label |
| `proxy` | `false` | Fetch through the server instead of from the browser |

A field declares **either** `options` **or** `optionsSource`, never both — a definition that
declares both is rejected.

Because `url`, `headers` and `body` interpolate `${state.x}`, one field's choices can depend on
another's answer, and the list refetches when that answer changes:

```yaml
optionsSource:
  url: /api/cities?country=${state.country}
  valuePath: id
  labelPath: name
```

The engine only carries the descriptor — it never calls the endpoint. The fetch is the renderer's,
which is [mateu's `@RestOptions` / `RestDataSource`](https://mateu.io/java-ui-definition/annotations/rest-options/)
machinery: a form talking to any REST API without the backend in the middle.

:::caution[Who fetches, and what that costs]
By default the **browser** calls the endpoint, so it must be reachable from there and allow CORS
from wherever the UI is served, and a credential in `headers` is a credential you have handed to
the client.

`proxy: true` moves the fetch to the **server**: no CORS, server-to-server, and a
`${secret.X}` placeholder in the url or a header is resolved there — from a `SecretsProvider` bean
or the environment variable of that name — instead of travelling to the browser. Use it for
anything authenticated, and keep the direct mode for public endpoints.

```yaml
optionsSource:
  url: https://pms.internal/hotels?token=${secret.PMS_TOKEN}
  valuePath: code
  labelPath: name
  proxy: true
```

What the server fetches is only ever what **this stored definition** declares. The task page tells
mateu so by implementing `RestSourceSupplier`, which is how a view assembled at runtime declares
what an annotated one declares with `@RestOptions` — mateu never takes a proxied endpoint from the
request, or the proxy would be an open relay.
:::

## Loading form definitions

Forms reach the engine two ways: from **directories on disk**, and from **Git repositories**. Both
run at startup, and both work the same in memory and in JPA mode. (There is no classpath loading
for forms — a `src/main/resources/forms/` directory is not read, unlike `classpath:/workflows/` on
the engine side.)

A Git import can also be re-run on demand with the MCP tool `importFormsFromGit` ("Import the
latest form definitions from Git") or by a webhook.

## Importing from a directory

```yaml
forms:
  directory-import:
    directories:
      - /definitions/forms
```

or, in the standalone app, `FORMS_DEFINITIONS_DIRS=/definitions/forms` (comma-separated).

Each directory is scanned recursively for `.json` / `.yaml` / `.yml` / `.ecform` files, and every
file that has both `name` and `fields` is imported — so a directory holding workflow definitions
next to forms is harmless. A directory that is not there is reported as an error rather than passed
over in silence.

Forms removed from the directory are **deleted** on the next import, tracked separately per
directory, and only ever forms that this import created.

:::tip[Directory or Git?]
Git import reads what is **committed**; a directory is read as it is. Point the engine at a
directory while authoring a form, at a repository when shipping it.
:::

## Importing from Git

The forms engine can clone one or more Git repositories at startup and import every `.json` / `.yaml` / `.yml` / `.ecform` file that contains a valid form definition (i.e. has both `name` and `fields` fields).

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

The platform UI includes a **visual form editor** for building forms without writing the definition by hand: add and reorder fields, set their data type and stereotype, and — for the stereotypes that pick from a list — edit the value/label pairs of their choices, with a live preview that renders the form as the user will see it. Changes are persisted back as JSON definitions. Access it from the management UI under Form Definitions.
