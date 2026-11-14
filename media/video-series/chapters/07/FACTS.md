# Chapter 7 — facts checked (against v2.41.0) — depends on PR #657 (unmerged at writing)

| Claim | Source |
|---|---|
| USER_TASK with `formId`, `assignment.candidateGroups`, `escalation`, `timeout` | `examples/video-series/.../hotel-booking.ec.yaml` 11–21; CHANGELOG 2.31.0 l. 301 |
| `notify` changes nothing on the task; also `reassign`, `escalateTo` | `doc/.../guides/human-tasks-assignment-and-escalation.md` 91–95 |
| Escalations/timeouts are engine timers on the step; a restart neither loses nor repeats them | same guide 99–102 |
| Forms engine serves the form in the same app (embedded) | CHANGELOG 2.40.0 l. 61 |
| Submitted values become process variables | `doc/.../guides/user-tasks.md`; filmed (BOOKING-7 goes on) |
| Task inbox (Forms → Tasks), claim, complete | `user-tasks.md` 50–54; **the Run → form route needs PR #657** (on main today Run answers "Page not found") |
| Complete step by hand, reason mandatory, audited; Operations tab | CHANGELOG 2.30.0 (#545, l. ~322); dialog text "Mandatory: recorded with your name in the process's audit trail" |
| Test kit: `advanceTime`, `hasEscalated` | CHANGELOG 2.41.0 l. 38; `examples/workshop-hotel-booking/.../TimeTest.java` 34–47 (same definition as the series app's, diffed) |
| `trust-forwarded-token` is laptop-only | `examples/video-series/src/main/resources/application.properties` (forms section); design/video-series decision "Chapter 7 sign-in" |
