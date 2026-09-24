---
title: Payload Templates
description: Build JSON (or any text) from a process's state — for REPLY, PUBLISH_EVENT and HTTP_CALL — with sandboxed JEXL expressions.
---

A **payload template** turns the process's state into a payload: the reply a `REPLY` step gives, the data of an event a `PUBLISH_EVENT` step publishes, the body (and URL, query and headers) of an `HTTP_CALL`. Expressions go in `${…}` and are JEXL, evaluated in the same sandbox as every other expression in a definition — no loops, lambdas, side effects or reflection, bounded size — because a definition may come from git.

## Structured templates — JSON with typed leaves

Write the payload as JSON/YAML; string leaves may hold expressions:

```yaml
body:
  orderId: "${orderId}"                          # "O-1"          (a string variable stays a string)
  total: "${amount * 1.21}"                      # 121.0          (a number, not "121.0")
  items: "${items}"                              # [{"sku":"A"}]  (a variable holding a JSON array is parsed)
  express: "${shipping == 'express'}"            # true
  customer: { id: "${customerId}", tier: "${tier ?: 'standard'}" }
  note: "Order ${orderId} for ${customerName}"   # interpolated text
  source: eventconductor                         # constant
```

- A leaf that is **exactly one** `${…}` keeps the value's **type**. Process variables are strings, so a variable that holds a JSON object or array is parsed into it; arithmetic (`${amount * 1}`) makes a number.
- A leaf with text around its expressions is a **string**.
- Anything that is not a string is a constant. The result is always valid JSON — no escaping to get wrong.
- `$${` is a literal `${`.

## Text templates

For XML, form bodies or any other format: `bodyTemplate` / `payloadTemplate`, interpolation only — the author owns the format.

```yaml
bodyTemplate: "<order id=\"${orderId}\" total=\"${amount}\"/>"
```

## What an expression sees

| name | value |
|---|---|
| each process variable | its value (a string) |
| `process` | `id`, `businessKey`, `workflowDefinitionId`, `version` |
| `step` | `id`, `name`, `executionId` |
| `now` | the current instant, ISO-8601 |
| `businessKey` | the process's business key |

Nothing else — no environment, no secrets.

## Validation and failures

Every `${…}` is parsed at build time (the [Maven plugin](/reference/maven-plugin/)) and when the definition is imported; a template that does not parse is an error. A template that cannot be *evaluated* at run time fails the step (so retries and compensation apply) — it never yields a silently empty payload. An undefined variable is not an error: it reads as `null`.
