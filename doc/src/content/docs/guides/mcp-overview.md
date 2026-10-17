---
title: AI Integration Overview
description: Native Model Context Protocol (MCP) integration — the key differentiator.
---

EventConductor is, to our knowledge, the **first workflow engine with native [Model Context Protocol (MCP)](https://modelcontextprotocol.io) integration**.

Every module exposes its domain as MCP tools. Any MCP-compatible AI client — Claude Desktop, a custom chatbot, an internal copilot — can connect and operate the engine in natural language, with no custom integration code.

## Architecture

```
AI client (Claude Desktop, chatbot, copilot…)
        │  MCP over SSE
        ▼
ia-agent-service  (port 8095)
        │  opens a fresh SSE connection per prompt
        ├──► orchestrator MCP server  (port 8105)  — workflow engine tools
        ├──► forms-engine MCP server  (port 8106)  — form definition & execution tools
        └──► booking-service MCP server (port 8108) — domain-specific business tools
```

## Available MCP tools

### Workflow engine (`orchestrator`, port 8105)

| Tool | Description |
|---|---|
| `listProcesses` | All process instances with status and completion % |
| `getProcessDetails` | Full process detail: variables + all step executions |
| `findProcessByBusinessKey` | Look up a process by its business key |
| `getProcessLogs` | Audit trail and log messages for a process |
| `retryProcess` | Pick a stopped process up where it stopped: the failed (or cancelled) steps run again, the ones that succeeded are left alone. `ERROR` or `CANCELLED` only |
| `restartProcess` | Run a stopped process again from the beginning, the steps that already succeeded included, with the variables it was created with. `ERROR` or `CANCELLED` only |
| `sendMessage` | Send an external message to resume processes waiting on a `WAIT_FOR_MESSAGE` step |
| `getWorkflowAnalytics` | Per-definition analytics: counts by status, rates, throughput per day, avg/p95 durations, bottleneck step ([guide](/guides/analytics/)) |
| `findBottleneck` | Where processes get stuck: slowest step, steps with waiting instances, steps with failures |
| `importWorkflowDefinitionsFromGit` | Pull and upsert workflow JSON files from Git |
| `listWorkflowDefinitions` | The definitions (id, name, status, whether they accept new instances) — what a schedule can start |
| `listSchedules` / `getSchedule` | Every schedule, optionally of one workflow: cron (and in words), zone, variables, source, whether it is editable, paused, next starts |
| `createSchedule` / `updateSchedule` / `deleteSchedule` | Manage the schedules created through the engine (source UI/API). A schedule declared in a definition or a `cron.yaml` is owned by its file and refused here |
| `pauseSchedule` / `resumeSchedule` | Pause or resume any schedule; the pause is kept by id and survives re-imports |
| `runScheduleNow` | Start the schedule's workflow once, now, with its variables |
| `listEventTriggers` | Every event trigger, optionally of one workflow — declared in the definition or in an `events.yaml`: destination and topic, type, filter, business key, variables, source, status ([guide](/guides/domain-event-triggers/)) |
| `pauseEventTrigger` / `resumeEventTrigger` | Pause or resume a trigger; while paused, matching events start nothing and are not replayed |
| `listReceivedEvents` | The latest received domain events and what each did: started a process, delivered a message, or the error |
| `publishInboundEvent` | Inject a domain event on a destination (for tests and systems without Kafka); returns what it started and delivered |
| `exportScheduleYaml` | The schedule's `cron.yaml` entry, id included, to promote it to git ([lifecycle](/guides/starting-a-process/#managing-schedules-from-the-ui-the-rest-api-and-mcp)) |

### Forms engine (`forms-engine`, port 8106)

| Tool | Description |
|---|---|
| `listForms` | All form definitions with field count |
| `listFormExecutions` | All pending/completed user tasks |
| `getFormExecution` | Full detail of a form execution including submitted values |
| `importFormsFromGit` | Pull and upsert form JSON files from Git |

### Rule engine (`rules`, port 8107)

| Tool | Description |
|---|---|
| `listRules` | All rule definitions in the catalog |
| `getRule` | Full definition of a rule as canonical JSON |
| `saveRule` | Create or update a rule definition (JSON or YAML, schema-validated) |
| `validateRule` | Validate a rule definition without saving it |
| `deleteRule` | Delete a rule definition by id |
| `evaluateRule` | Evaluate a rule against a JSON object of facts |
| `importRulesFromGit` | Pull and upsert rule JSON/YAML files from Git |

### Custom domain tools (example: `booking-service`, port 8108)

| Tool | Description |
|---|---|
| `createBooking` | Create a new booking for a lead |
| `listBookings` | All bookings with status |
| `getBooking` | Full booking detail |
| `changeBookingStatus` | Change booking status (Pending / Confirmed / Cancelled) |

## What you can say

| Natural language | What happens |
|---|---|
| "What is the status of order 123?" | Queries process by business key, returns status + variables |
| "Retry all failed processes from today" | Calls `retryProcess` for each ERROR process |
| "Show me the pending user tasks for the onboarding workflow" | Lists form executions filtered by workflow |
| "Import the new workflow definitions from Git" | Triggers `importWorkflowDefinitionsFromGit` |
| "Cancel booking B-456 and tell me why it failed" | Changes booking status + reads process logs |

## Resilience

The agent is designed to be **resilient to MCP server failures**: if one MCP server is down, the others continue working normally. Each server self-describes its domain via a `system-context` MCP Prompt, so the LLM always has up-to-date context without any code changes.

## Key design decisions

| Decision | Reason |
|---|---|
| Fresh SSE connection per prompt | A persistent SSE connection breaks if the MCP server restarts. Opening a fresh connection per request makes the agent resilient to server restarts. |
| Dynamic system prompt | Each MCP server exposes a `system-context` MCP Prompt. The agent reads it on each request and combines it with a base prompt — no code changes needed when servers change. |
| Anthropic Prompt Caching | The system prompt is cached server-side (Anthropic cache). Cached tokens cost ~10% of normal price, significantly reducing cost when the system prompt is stable. |
| Conversation history | The last 5 exchanges are stored per session (Caffeine cache), giving the LLM context across a conversation. |
