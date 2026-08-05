---
title: Versioning & Compatibility
description: What EventConductor 1.0 promises to keep stable, what it does not, and how the format is versioned.
---

EventConductor follows [Semantic Versioning](https://semver.org). From **1.0.0** onward, the
guarantees below define what "a breaking change" means for this project — a MAJOR bump — so you can
upgrade a PATCH or MINOR release without changing your definitions, configuration or workers.

This page is deliberately explicit about **what is covered and what is not**. A stability promise is
only useful if its boundary is clear.

## What is stable (the public contract)

These are covered by SemVer. A backward-incompatible change to any of them is a MAJOR release.

- **The workflow, rule and form definition formats** — the JSON/YAML (`.ec`) documents you author,
  as described by the published JSON Schemas. This is the primary contract: it is both the design an
  analyst reads and the artifact the engine runs. Each format carries a versioned schema `$id`
  (`urn:eventconductor:workflow-definition-schema:1`, `…:rule-schema:1`, `…:form-schema:1`) — see
  [Format versioning](#format-versioning) below.
- **Configuration properties** — the documented `workflow.*`, `forms.*` and `rules.*` properties on
  the [Configuration](/reference/configuration/) page. Documented defaults will not change under a
  MINOR/PATCH in a way that changes runtime behaviour.
- **The worker integration contract** — how a worker receives a task and reports its result: the
  task/reply protocol and, in `kafka` mode, the [Kafka topics](/reference/kafka-topics/) and message
  envelopes on them.
- **The embedded entry points** — the documented `@WorkflowEmbeddedApplication` annotation and the
  published integration ports (e.g. the `EmbeddedTaskExecutor` callback), as described on the
  [Java API](/reference/java-api/) page.
- **The HTTP message API** (`POST /workflow/api/messages`) and the **MCP tool** surface.
- **Metric names** — the `eventconductor.*` meters on the [Observability](/reference/observability/)
  page. New meters may be added under a MINOR; existing names/meanings are stable.
- **Process and step statuses** — the status values on the [Statuses](/reference/statuses/) page.
  New statuses may be added under a MINOR (treat unknown statuses defensively); existing ones do not
  change meaning.

## What is NOT stable (implementation details)

These are **not** part of the public API. They can change in any release, including PATCH. Do not
build against them:

- **The Java model classes as imported types.** `WorkflowDefinition`, `Step` and the other
  `io.mateu.workflow.domain.*` / `dtos.*` records are internal. They carry UI-framework annotations
  and helper wiring, and their shape follows the engine's needs, not a published contract. **Program
  against the JSON/YAML definition format, not against these Java types.** If you construct
  definitions in code today, treat that as coupling to an internal detail.
- **Everything under `io.mateu.workflow.infra.*`, `application.*` and `domain.*`** — use cases,
  handlers, repositories, services, aggregates. These are wiring, not API. In particular, the
  callback the embedded `EmbeddedTaskExecutor` documentation mentions is reached through the
  published port, not by importing internal use-case classes directly.
- **The database schema.** Tables, columns and indexes are managed by Flyway and evolve with the
  engine. Read process state through the engine (its UI, MCP tools or HTTP API), not by querying the
  tables. Flyway migrations are forward-only and additive within a MAJOR line (see below).
- **Wire formats on internal topics** beyond the documented worker envelopes, and the exact text of
  log messages.

## Format versioning

Each definition format has a version, carried in its schema `$id` (the `:1` suffix). Version **1**
is the 1.0 format.

- **Additive, backward-compatible changes stay v1.** New optional fields may be introduced under a
  MINOR release; a document that does not use them keeps validating, and an older engine ignores
  fields it does not know only where a format explicitly allows it.
- **A backward-incompatible format change bumps the schema version** (`…-schema:2`) and ships with a
  migration note. The engine will state which format versions it accepts.
- The document-level `version` field on a `WorkflowDefinition` is unrelated to the format version:
  it is the engine-assigned **instance revision counter** for a stored definition, not the schema
  version.

## Database upgrades

Flyway migrations run automatically on startup (`workflow.persistence=jpa`). Within a MAJOR line
they are **forward-only and additive** — a newer engine upgrades an older schema in place with no
manual step, and no migration drops or rewrites existing data. Always take a backup before a MAJOR
upgrade. Run one engine version at a time through the migration; skipping intermediate MAJORs is not
supported unless a release note says otherwise.

## Deprecation policy

Something on its way out is marked `@Deprecated` in the Java surface (where it applies) and called
out in the [changelog](https://github.com/miguelperezcolom/eventconductor/blob/main/CHANGELOG.md)
and the relevant reference page, with the replacement named. A deprecated part of the public
contract keeps working for at least one MINOR release before it can be removed in a MAJOR. Legacy
aliases already in place (for example the `MESSAGE` → `WAIT_FOR_MESSAGE` step-type name) are honored
under this policy.

## Pre-1.0 releases

`1.0-beta.*` releases predate these guarantees and did not promise compatibility between betas. The
guarantees on this page begin at **1.0.0**.
