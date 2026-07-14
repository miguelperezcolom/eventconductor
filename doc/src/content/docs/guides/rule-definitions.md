---
title: Rule Definitions
description: Defining business rules (expression rules and decision tables) in the EventConductor rule engine. Supports JSON and YAML.
---

The rule engine is the **catalog** of business rules: it stores, validates, versions and serves rule definitions — it does not execute them. Evaluation happens in the embeddable [rule runtime](/guides/rule-evaluation/), wherever your data lives.

Rules can be written in **JSON** or **YAML** (`.json`, `.yaml`, `.yml`), stored in version control, and referenced by `RULE` steps in workflow definitions. They can be imported from Git at startup, on demand via the MCP tool `importRulesFromGit`, or automatically via a **GitHub webhook** (`POST /rules/webhooks/github`, HMAC-signed with `rules.git-import.webhook-secret`).

There are two rule types:

- **`expression`** — a `when` condition plus `then` output assignments, all in [JEXL](https://commons.apache.org/proper/commons-jexl/) (the same expression language used by workflow step preconditions).
- **`decision-table`** — input/output columns and rows of cases, with a `FIRST` or `COLLECT` hit policy.

## Expression rules

```yaml
id: high-value-order
name: High value order approval
description: VIP orders over 100 get a discount and need approval
type: expression
salience: 10
tags: [orders]
when: "order.total > 100 && customer.category == 'VIP'"
then:
  - name: discount
    expression: "order.total * 0.1"
  - name: approvalRequired
    expression: "true"
```

If `when` is omitted the rule always matches. Each `then` assignment produces one output, evaluated against the facts.

## Decision tables

```json
{
  "id": "shipping-costs",
  "name": "Shipping costs",
  "type": "decision-table",
  "hitPolicy": "FIRST",
  "inputs": ["customer.category", "order.total"],
  "outputs": ["shippingCost", "courier"],
  "rows": [
    { "when": ["VIP", "*"],     "then": ["0",  "'express'"]  },
    { "when": ["*",   "> 100"], "then": ["5",  "'standard'"] },
    { "when": ["*",   "*"],     "then": ["10", "'standard'"] }
  ]
}
```

Each row's `when` has one cell per input, and its `then` one JEXL expression per output.

**Cell semantics** (compiled to JEXL against the input column):

| Cell | Meaning |
|---|---|
| `*` or blank | always matches |
| `> 100`, `>= 10`, `< 5`, `<= 1`, `!= 0`, `== 3` | comparison against the input |
| `100`, `99.5` | numeric equality |
| `'express'` | equality, as written |
| `VIP` | string equality |

**Hit policies:**

- `FIRST` (default): the first matching row wins; its outputs are the result.
- `COLLECT`: all matching rows are gathered; the merged outputs are last-write-wins in row order, and every row's outputs are also available individually in the evaluation result.

## Top-level fields

| Field | Type | Description |
|---|---|---|
| `id` | string | Unique identifier. Auto-generated on save if omitted. |
| `name` | string | **Required.** Human-readable name. |
| `description` | string | Optional description. |
| `type` | string | **Required.** `expression` or `decision-table`. |
| `version` | integer | Definition version number. |
| `salience` | integer | Priority for group (tag) evaluation: higher runs first. |
| `tags` | string[] | Tags for evaluating groups of rules together. |
| `when` / `then` | — | Expression rules only. |
| `inputs` / `outputs` / `rows` / `hitPolicy` | — | Decision tables only. |

Definitions are validated on save against the JSON schema (`rule-schema.json`) plus semantic checks: row arity must match `inputs`/`outputs`, and every expression must parse as valid JEXL.

## Storage, API and UI

- `rules.persistence=memory` (default) or `jpa`.
- Read API for remote runtimes: `GET /rules` (optional `?tag=`), `GET /rules/{id}` — and the same over **gRPC** (`RuleService.GetRule` / `ListRules`) when `rules.grpc.enabled=true` (port `rules.grpc.port`, default 9090).
- Management UI at `/_rules` (Mateu), with the definition editable as JSON/YAML.
- MCP tools: `listRules`, `getRule`, `saveRule`, `validateRule`, `deleteRule`, `evaluateRule`, `importRulesFromGit`.
- On every save/delete in Kafka mode the catalog emits `RulePublished` / `RuleDeleted` on the `rules` destination, so remote runtimes refresh their caches instantly.

## Git import

```yaml
rules:
  git-import:
    # webhook-secret: mysecret   # optional, for the GitHub webhook
    repositories:
      - url: https://github.com/your-org/your-rules-repo.git
        branch: main
        # username / password for private repos
```

At startup (and on webhook or MCP request) every `.json`/`.yaml`/`.yml` file with a `name` and a rule `type` is validated and upserted into the catalog.
