---
title: Introduction
description: What is EventConductor and why use it?
---

EventConductor is a production-grade, event-driven workflow orchestration platform for the Java/Spring ecosystem. It covers the full lifecycle of a business process — from definition to execution to monitoring — without forcing you into BPMN's complexity or an external SaaS dependency.

## What problems does it solve?

Most workflow engines fall into one of two traps:

- **Too heavy**: BPMN-based engines (Camunda, Flowable) require dedicated XML diagrams, separate process engines, and steep learning curves for developers.
- **Too coupled**: SaaS solutions (Temporal Cloud, Conductor Cloud) introduce external dependencies and vendor lock-in.

EventConductor sits in the middle: a **library** you embed in your Spring Boot application, with a JSON DSL designed to be written and reviewed by developers, and three deployment modes that scale from a unit test to a multi-pod Kubernetes cluster.

## Core concepts

### Workflow Definition

A JSON file that describes the steps of a business process — their types, ordering, timeouts, retries, and branching conditions. Definitions are versioned and stored in version control.

### Process Instance

A running execution of a workflow definition. Each instance has its own variables, status, and step execution history.

### Step Execution

The execution of a single step within a process. Steps can be:

- **ACTION** — dispatches work to a worker microservice
- **USER_TASK** — pauses the workflow until a human submits a form
- **PROCESS** — starts a child sub-process
- **FORK / JOIN** — parallel execution branches
- **END** — marks the workflow as complete

### Worker

A stateless microservice (or a Spring bean) that receives `TaskExecutionRequested` events, performs business logic, and reports back a `TaskStatusChanged` event with the outcome and output variables.

### Forms Engine

A companion module that manages form definitions and form executions. When a USER_TASK step starts, the engine creates a `FormExecution` and pauses the workflow until the user submits the form.

## How it compares

| Feature | EventConductor | Camunda | Temporal |
|---|---|---|---|
| Definition format | JSON (code-friendly) | BPMN XML (diagram-first) | Code (Go/Java SDK) |
| Deployment | Embedded library | Separate server | Separate server / SaaS |
| External dependencies | None / PostgreSQL / Kafka | Database + engine | Temporal server |
| Human tasks (forms) | Built-in | Built-in | Manual |
| AI / MCP integration | Native | None | None |
| Learning curve | Low | High | Medium |

## Key differentiator: native AI via MCP

EventConductor is, to our knowledge, the first workflow engine with a native [Model Context Protocol (MCP)](https://modelcontextprotocol.io) integration.

Every module exposes its domain as MCP tools. Any MCP-compatible AI client — Claude Desktop, a custom chatbot, an internal copilot — can connect and operate the engine in natural language:

| What you say | What happens |
|---|---|
| "What is the status of order 123?" | Queries process by business key, returns status + variables |
| "Retry all failed processes from today" | Calls `retryProcess` for each ERROR process |
| "Show me the pending user tasks for the onboarding workflow" | Lists form executions filtered by workflow |
| "Import the new workflow definitions from Git" | Triggers `importWorkflowDefinitionsFromGit` |

No other workflow engine — Camunda, Temporal, Netflix Conductor — offers this out of the box.
