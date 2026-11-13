---
title: "7 · Operating"
description: Find a booking by its business key, repair a run by hand, act on many runs at once, and let an AI assistant do it in natural language.
---

A process in production is not finished when it is written: somebody asks where a booking is, a hotel outage fails forty bookings overnight, a guest changes their mind by phone. The engine gives operations its tools from day one.

## Find a booking

**Workflow → Processes** lists every run — yours and their forEach children — with status and progress:

![The process list](/workshop/07-process-list.jpg)

Type a business key in the search box to find one booking:

![Searching for BOOKING-5](/workshop/07-search-business-key.jpg)

Over the API: `GET /workflow/api/processes?…` (see the [Processes REST API](/reference/processes-rest-api/)), and from MCP, `findProcessByBusinessKey`. For searches on your own data — *every booking of guest X*, *bookings above 1,000 €* — declare [search attributes](/guides/searching-and-bulk-operations/#search-attributes).

## Repair one run

A run's page has the operator's actions, each **audited** with your name and a mandatory reason:

| Action | When |
|---|---|
| **Retry from failure** | The cause is gone (the hotel is back): run the failed steps again, keep the ones that succeeded |
| **Retry from step** | Run again from a chosen step and everything that depends on it |
| **Complete step** | The work was done another way (the guest paid by phone): mark the step done, with its output variables — the console's way to decide the approval of module 6 without the form |
| **Set variables** | Correct the data the next steps will use (a wrong room number) |
| **Migrate…** | Move the run onto a newer version of the definition — see [module 10](/workshop/10-changing-without-redeploying/) |
| **Pause / Cancel** | Hold it, or stop it. Cancelling never rolls back |

![Retry from step: choose the step, give a reason](/workshop/07-retry-from-step.jpg)

The same are REST calls (`POST /workflow/api/processes/{id}/retry`, `…/steps/{stepExecutionId}/complete`, …) — see [Operating running processes](/guides/operating-running-processes/).

## Repair many runs at once

The process list's **Retry all matching** and **Cancel all matching** act on every run the current search matches, as a **batch operation**: it runs in the background, reports progress, skips what does not apply and says why. Last night's forty failed bookings are one action. See [Batch operations](/guides/searching-and-bulk-operations/#batch-operations).

## Ask an assistant

Every operation above is also an [MCP](/guides/mcp-overview/) tool, so an AI assistant can operate the engine in natural language. The workshop app serves MCP at `http://localhost:8095/sse`; in Claude Desktop's `claude_desktop_config.json`:

<!-- ec-example: skip -->
```json
{
  "mcpServers": {
    "hotel-workshop": { "url": "http://localhost:8095/sse", "type": "sse" }
  }
}
```

Then ask, for example:

- *"What happened to booking BOOKING-5?"* — `findProcessByBusinessKey`, `getProcessDetails`, `getProcessLogs`
- *"Which bookings failed today, and why?"* — `listProcesses` with `status: ERROR`
- *"Retry every failed hotel booking from the hotel call."* — `createBatchOperation`

:::caution
This workshop app accepts anonymous callers, so an assistant connected to it can do anything an operator can. In a real deployment, MCP sits behind authentication and the same roles as the console — see [MCP overview](/guides/mcp-overview/).
:::

## Check

- [ ] You can find BOOKING-5 by its business key.
- [ ] You know which action you would use if the hotel had been down for an hour (*Retry from failure*, or *Retry all matching* for all of them).
