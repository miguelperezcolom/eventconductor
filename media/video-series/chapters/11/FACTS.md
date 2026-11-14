# Chapter 11 — facts checked

| Claim | Source |
|---|---|
| Process list: business key column, search by key, "Only errors" filter | CL 2.41.0 (:45); console `ProcessFilters.java:36-59`; recorded |
| Errors tab: HTTP 503 after the step's retries ran out | SA channel-sync.ec.yaml (`retries: 2`); recorded `HTTP_503 … answered 503` |
| Incidents grouped by workflow, step and cause; one incident, three processes | CL 2.30.0 (:321); `IncidentRow.java`; recorded |
| "Retry all" with a reason → a RETRY batch over the incident; batch page shows progress | CL 2.30.0 (:316, :323); `IncidentDetailView.java:111`, `RetryIncidentForm`; recorded (`RETRY · Completed · 3/3`) |
| Retry from failure: succeeded steps are not run again | console `Processes.java:163-164`; docs/guides/operating-running-processes.md |
| Complete step by hand; reason mandatory, recorded with your name; audit in Operations tab | operating-running-processes.md:11, :16, :125-133; `CompleteStepForm.java:47-48`; recorded (`[complete-step] by anonymous … reason: …` — anonymous because the series app is laptop-only) |
| Pause, set variables, retry from a step, cancel: same reason/audit rule | CL 2.30.0 (:318-319); console `SimpleProcessViewModel.java:586-717` |
| Definitions imported from Git; webhook → new version; no build, no restart | docs/workshop/10-changing-without-redeploying.md; docs/guides/workflow-definitions.md:358-388; recorded (`202`, v2 in Versions) |
| Identical content from Git does not mint a version (v1 stays v1) | CL 2.41.0 Fixed |
| Running processes keep their version; Versions tab (JPA) | workflow-definitions.md:265-271; workshop/10 :98 |
| Migrate…: steps mapped by id, reason mandatory, Preview = dry run; many at once via batch | `MigrateForm.java` (Preview :102, Migrate :115); CL 2.31.0 (:294-298); recorded (`v1 → v2: can be migrated · approve → approve: KEEP_TASK`) |

Seed data: SA scripts/seed.sh. The Git repository of definitions is created by `before` in script.json (build/hotel-definitions).
