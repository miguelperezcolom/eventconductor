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

The process tools have REST twins on the same application services — start, read, list, cancel, pause, resume, retry, restart and retry-step under [`/workflow/api/processes`](/reference/processes-rest-api/) — so an operation behaves the same whichever door it comes through: the same status rules, the same idempotency on the business key, and (with `workflow.security.flow-authorization.enabled`) the same definition requirements. The REST API answers synchronously what a tool reports in prose: `404` for an unknown process, `409` for a status that does not allow the command.

### Workflow engine (`orchestrator`, port 8105)

| Tool | Description |
|---|---|
| `listProcesses` | Process instances with status and completion %; optionally filtered by `workflowDefinitionId`, `status`, `tag` and `query` — [search-attribute](/guides/searching-and-bulk-operations/) conditions such as `customerId = "C-9" AND amount > 100` (then one page: `page`, `size`) |
| `createBatchOperation` / `listBatchOperations` / `getBatchOperation` / `cancelBatchOperation` | Cancel, retry, restart, pause, resume, set variables or retry from a step for every process a filter matches, in the background; follow its progress, skip reasons and errors; cancel it ([guide](/guides/searching-and-bulk-operations/#batch-operations)) |
| `migrateProcessesInBatch` | A `MIGRATE` batch: move every live process of a definition (of one version, `fromVersion`) onto another version with one step mapping |
| `getProcessDetails` | Full process detail: variables + all step executions |
| `findProcessByBusinessKey` | Look up a process by its business key |
| `getProcessLogs` | Audit trail and log messages for a process |
| `listInFlightProcesses` | Processes in flight (`PENDING`, `RUNNING`, `PAUSED`) from the process-index read model — one indexed read; empty when `workflow.projection.enabled` is off |
| `countProcessesByStatus` | Process counts by status across the fleet, from the process-index read model; empty when the projection is off |
| `retryProcess` | Pick a stopped process up where it stopped: the failed (or cancelled) steps run again, the ones that succeeded are left alone. `ERROR` or `CANCELLED` only |
| `restartProcess` | Run a stopped process again from the beginning, the steps that already succeeded included, with the variables it was created with. `ERROR` or `CANCELLED` only |
| `setProcessVariables` | Change a live process's variables (`set`, `remove`) — an audited admin operation; `reason` mandatory ([guide](/guides/operating-running-processes/)) |
| `revealProcessVariable` | The value of one sensitive variable (shown as `••••` by every other tool) — needs a `workflow.security.sensitive.reveal-roles` role and a `reason`; audited ([guide](/guides/sensitive-data-and-encryption/)) |
| `completeStepManually` | Complete a step execution by hand with output variables, as if its worker had answered; the flow carries on |
| `retryProcessFromStep` | Run a live process again from one of its steps: it and everything downstream start over |
| `dryRunProcessMigration` / `migrateProcess` | Plan, then carry out, moving a live process onto another version of its definition — a step mapping, variables, the reason ([guide](/guides/migrating-running-processes/)) |
| `listIncidents` / `getIncidentProcesses` / `retryIncident` | Failures grouped by definition, step and error family; their processes; retry a whole group — as a RETRY batch operation, whose id it returns |
| `pauseProcess` / `resumeProcess` | Pause a `PENDING` or `RUNNING` process (in-flight work finishes, successors held, clocks frozen) and resume it |
| `pauseWorkflow` / `resumeWorkflow` | Pause/resume a whole definition: all its processes, with new instances born paused |
| `sendMessage` | Send an external message to resume processes waiting on a `WAIT_FOR_MESSAGE` step |
| `getWorkflowAnalytics` | Per-definition analytics: counts by status, rates, throughput per day, avg/p95 durations, bottleneck step ([guide](/guides/analytics/)) |
| `findBottleneck` | Where processes get stuck: slowest step, steps with waiting instances, steps with failures |
| `importWorkflowDefinitionsFromGit` | Pull and upsert workflow JSON files from Git |
| `getAgentTranscript` | The transcript of an `AGENT_CALL` step execution: goal, model turns, tool calls and results, tokens |
| `listWorkflowDefinitions` | The definitions (id, name, status, whether they accept new instances) — what a schedule can start |
| `listSchedules` / `getSchedule` | Every schedule, optionally of one workflow: cron (and in words), zone, variables, source, whether it is editable, paused, next starts |
| `createSchedule` / `updateSchedule` / `deleteSchedule` | Manage the schedules created through the engine (source UI/API). A schedule declared in a definition or a `cron.yaml` is owned by its file and refused here |
| `pauseSchedule` / `resumeSchedule` | Pause or resume any schedule; the pause is kept by id and survives re-imports |
| `runScheduleNow` | Start the schedule's workflow once, now, with its variables |
| `createEventTrigger` / `updateEventTrigger` / `deleteEventTrigger` / `exportEventTriggerYaml` | Create a trigger (source UI/API) that starts a workflow on a domain event, edit or delete one created that way, and get its `events.yaml` entry to promote it to git ([guide](/guides/domain-event-triggers/#triggers-created-from-the-ui-the-rest-api-and-mcp)) |
| `listEventTriggers` | Every event trigger, optionally of one workflow — declared in the definition, in an `events.yaml` or created through the engine: destination and topic, type, filter, business key, variables, source, status ([guide](/guides/domain-event-triggers/)) |
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
