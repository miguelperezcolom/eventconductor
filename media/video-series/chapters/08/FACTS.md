# Chapter 8 — facts checked (against v2.41.0)

| Claim | Source |
|---|---|
| TIMER `until` a moment: date, calendar offset, `at`, zone | `doc/.../guides/scheduling-and-deadlines.md` 8–26; CHANGELOG 2.22.0 l. 569 |
| A moment already past fires at once (`ifPast: fire` default) | `scheduling-and-deadlines.md` 44–52; filmed BALANCE-2 |
| `deadline` on a waiting step; `onTimeoutStepId` is a route, not a failure | `scheduling-and-deadlines.md` 60–76; `balance-reminder.ec.yaml` 28–51 |
| WAIT_FOR_MESSAGE correlated by business key | `balance-reminder.ec.yaml` 33; `scripts/message.sh` |
| Console shows when the timer is due | filmed popover ("Due 2026-…-… 09:00") |
| SEND_MESSAGE from one process to another, correlated by room; step `timeout` + own route | `guest-checkin.ec.yaml`, `room-cleaning.ec.yaml`; CHANGELOG 1.0-beta.013 l. 3746 |
| `cron.yaml` beside the definitions | CHANGELOG 2.26.0 l. 485 |
| Schedules page: declared in a file → pause, resume, run now | filmed detail page ("Declared in a file — change it there. Here it can be paused, resumed or started now.") |
| Run now starts it under its own business key | seed run (`nightly-occupancy-cron-manual-…`) |
