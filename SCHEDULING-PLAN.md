# Plan: moments in time — timers and deadlines relative to process dates

> Status: **DRAFT (2026-09-24)** — for review. Open questions in §6, each with a recommendation.
> One commit per phase; each phase compiles and tests green on its own.

## 1. Goal

Let a definition say **when**, in business terms, from the process's own data:

- *"Charge the balance **3 days before the guest's check-in**, at 09:00 hotel time."*
- *"The payment must arrive **by the day before check-in**, or cancel the booking."*
- *"If the booking was made too late for that, charge **now**" — or go down another branch.*
- *"If the guest **moves the check-in date**, the charge moves with it."*

Today a TIMER can wait a fixed `duration` or until the date in `untilVariable`, and every other step
has a fixed `timeout` in milliseconds. That does not cover an offset from a date, a time of day, a
time zone, what happens when the moment is already past, a deadline from a date, or a date that
changes while the step waits.

Agreed in conversation (2026-09-24):

| # | Decision |
|---|---|
| A | One structured way to say "a moment" — `{date, offset, at, zone}` — shared by TIMER (`until`) and by any step's deadline (`deadline`). Structured rather than a free JEXL expression: validated at build time, readable in the graph, completable in the IDE. `date` is a template, so an expression is still possible. |
| B | A moment already past when the step starts is explicit: `ifPast: fire \| route \| fail`. |
| C | A waiting timer and a pending deadline **follow the process's variables**: when the date they depend on changes, they are recomputed. |
| D | `untilVariable`, `duration` and `timeout` keep working unchanged. |
| E | `notBefore` on any step (sugar for a TIMER in front) — last phase, optional. Business calendars are out of scope; the format leaves room for `calendar:`. |

## 2. What exists to build on

- **TIMER** (`Step.timerDueAt`, `Step.deadlineAt`): due moment from `duration` (counted from
  `startedAt`) or `untilVariable` (ISO date / date-time / offset date-time, parsed in
  `Step.parseDateOrDateTime` — a bare date means **midnight in the server's zone**,
  `ZoneId.systemDefault()`). A missing or unparseable date fails the step at start.
- **Materialised deadline**: `StepExecution.deadlineAt` (`LocalDateTime`, server-local), computed at
  `start()` by `computeDeadline()` from `startedAt` + the **variable snapshot taken at start**
  (`StepExecution.variables`), indexed for the `TimeoutScheduler` range scan. The same column serves
  a TIMER's due moment, a step's `timeout` and a retry backoff.
- **Recompute hooks**: `StepExecution.rearmedFor(Process)` recomputes derived fields (deadline,
  message subscription) — called from `MessageSubscriptionService` whenever process variables change,
  and from `InFlightStepRearmRunner` at boot. Today its deadline recompute still reads the **start
  snapshot**, not the process's current variables — so a changed date does not move anything.
- **Pause/resume** shifts in-flight clocks by the pause (`StepExecution.withStartedAt`); an absolute
  `untilVariable` due moment is naturally not shifted.
- **Firing**: `CheckTimerUseCase.isDue` re-derives the due moment from the snapshot;
  `CompleteTimerStepHandler` re-checks it before completing. Timeouts go through `TimeoutScheduler` →
  `TIMEOUT` → retries → `onTimeoutStepId` or failure.
- **Templates** (`Templates`, `TemplateContext`) from the previous feature — `date` reuses them.
- **Validation** in one place for engine and Maven plugin (`WorkflowDefinition.checkInvariants`,
  `SpecValidator`, schema `ec.schema.json`).

## 3. Design

### 3.1 The moment spec (`domain/aggregates/Moment`, rules in `definition-analysis`)

```yaml
until:                        # on a TIMER
  date: "${checkinDate}"      # required: a template → ISO date, date-time or offset date-time
  offset: -P3D                # optional: ISO-8601 period/duration, may be negative (P1D, -PT2H, -P1DT6H)
  at: "09:00"                 # optional: local time of day, applied after the offset
  zone: "${hotelZone}"        # optional: IANA zone (template); default workflow.time.zone
  ifPast: fire                # optional: fire (default) | route | fail
  ifPastStepId: charge-now    # required iff ifPast = route
```

Resolution, in this order:

1. Render `date`; parse as offset date-time (keeps its instant), else date-time, else date
   (start of day) — the last two **in `zone`**.
2. Add `offset` — **calendar arithmetic in `zone`**: `-P3D` is three calendar days, so a DST change
   in between does not shift the hour; `PT…` parts are exact durations.
3. Replace the time of day with `at`, if given (in `zone`; a nonexistent local time on a DST gap
   moves forward, as `ZonedDateTime` does).
4. Convert to the engine's clock (server-local `LocalDateTime`, like every `deadlineAt` today).

Also accepted: `until: "${…}"` (a plain string = `{date: …}`), for the common case.

`workflow.time.zone` (default: the JVM zone) is the zone for dates without one — and makes today's
implicit `ZoneId.systemDefault()` explicit and configurable.

### 3.2 TIMER

- `until` joins `duration` / `untilVariable`; exactly one of the three. `untilVariable: x` stays as
  written and means `until: {date: "${x}"}`.
- **ifPast** (decided at `start()`, and again on a recompute — §3.4):
  - `fire` — due now: the timer completes and the flow carries on (today's behaviour).
  - `route` — the timer ends `TIMEOUT` with *"moment already past"*, and the flow continues at
    `ifPastStepId` **instead of** its successors — the `onTimeoutStepId` machinery (an exclusive
    route; the REPLY-uniqueness analysis treats it like the on-timeout route). There is no `SKIPPED`
    status in the engine, and none is added.
  - `fail` — the step ends `ERROR` (*"moment already past"*): retries do not apply, compensation does.
- Logged when armed: *"Timer armed for check-in − 3 days at 09:00 Europe/Madrid → 2026-07-29T09:00+02:00 (server 07:00Z)."*

### 3.3 `deadline` on any waiting step

```yaml
- id: wait-payment
  type: WAIT_FOR_MESSAGE
  messageName: payment-received
  deadline: { date: "${checkinDate}", offset: -P1D, at: "12:00", zone: "${hotelZone}" }
  onTimeoutStepId: cancel-booking
```

- Same spec as `until` (without `ifPast`: a deadline already past times the step out at once — see §6 Q3).
- Applies to every step that has a `timeout` today (ACTION, USER_TASK, HTTP_CALL, WAIT_FOR_MESSAGE,
  PROCESS, RULE…). `timeout` and `deadline` together → **the earlier wins**.
- **Retries**: a `timeout` restarts per attempt; a `deadline` does **not** — it is a business moment.
  A retry that would start after the deadline is not made; the step ends `TIMEOUT` and
  `onTimeoutStepId` / failure applies.
- USER_TASK: the deadline is also exposed on the form execution as its due date (inbox sorting).

### 3.4 Following the process's variables

- `StepExecution.rearmedFor(Process)` recomputes a TIMER's `until` and a step's `deadline` from the
  process's **current** variables (not the start snapshot) — only for `until` / `deadline`;
  `duration`, `timeout` and `untilVariable` keep their snapshot semantics (§6 Q1).
- The recompute is persisted with the variable change (it already is, for message correlation), and
  logged when the moment moves: *"check-in moved: timer now due 2026-08-02T09:00+02:00 (was …)."*
- A recompute that lands in the past on a waiting TIMER applies `ifPast` then; one whose date became
  missing/unparseable keeps the previous moment and logs a warning (never silently disarms).
- `CheckTimerUseCase.isDue` and `CompleteTimerStepHandler` read the **materialised `deadlineAt`**
  instead of re-deriving from the snapshot, so there is one source of truth.
- Needs a way to *change* the date while the step waits — already there: a `WAIT_FOR_MESSAGE` on a
  parallel branch, a message with `messageVariables`, or the variables API. The guide shows the
  "booking modified" pattern (a FORK with the timer on one branch and a modification listener on the
  other).

### 3.5 `notBefore` (optional, last)

`notBefore: {…moment…}` on any step: the step, once eligible, waits `PENDING` until the moment — a
TIMER in front, without drawing one. Implemented as a pre-start wait on the same `deadlineAt`
column. Decide after P1–P4 whether it earns its place.

### 3.6 Observability, UI, tooling

- Process view: the Timers / step detail show the spec in words and the resolved moment in the
  zone and in server time; the graph shows a clock badge with *"check-in − 3d, 09:00"*.
- Metric: `eventconductor.timer.rescheduled` (counter), `eventconductor.timer.fired{late}` (lateness
  histogram already implied by fire − due).
- Schema, IDE plugins, Maven-plugin validation (`MomentRules`: offset/at/zone syntax, zone exists
  when literal, `ifPastStepId` iff `route` and existing, `date` template parses).

## 4. Tests

- **Unit** — `MomentTest`: the four resolution steps; offsets negative/positive, days vs hours across
  a DST change (Europe/Madrid, last Sunday of March / October); `at` on a DST gap; offset date-time
  keeps its instant; zone from a variable; missing/unparseable date.
- **Unit** — `MomentRulesTest`: every validation rule. Existing TIMER tests untouched (compat).
- **E2e (embedded, controllable clock)** — check-in − 3 days fires at the right instant; `ifPast`
  fire/route/fail; `deadline` on WAIT_FOR_MESSAGE routes to `onTimeoutStepId`; deadline vs retries
  (no retry past it); timeout + deadline → earlier wins; **check-in moved** by a message → timer
  moves (later and earlier, including into the past → `ifPast`); pause/resume does not shift an
  absolute moment; restart (rearm runner) keeps the moment.
- **E2e JPA twin** of the reschedule case (the indexed `deadlineAt` is what the scheduler finds).
- **Dist** — a two-pod reschedule: the variable change is consumed by one pod, the timer fires once.

The engine has no injectable clock today (`LocalDateTime.now()` throughout), so P1 adds one — a
`Clock` bean, default `Clock.systemDefaultZone()` — used by `start()`, the schedulers and the timer
handlers; the e2e tests move it instead of sleeping.

## 5. Phases

- [ ] **P1 — Moment + clock.** `Moment` record and resolver (definition-analysis rules, engine
  resolver), `workflow.time.zone`, injectable `Clock`; `untilVariable` re-expressed through it
  (behaviour unchanged). Unit tests.
- [ ] **P2 — TIMER `until` + `ifPast`.** Step field, schema, invariants, plugin validation; `route`
  through the on-timeout route machinery; REPLY-uniqueness aware of it; e2e.
- [ ] **P3 — `deadline` on any step.** Earlier-wins with `timeout`; no retry past it; USER_TASK due
  date; e2e.
- [ ] **P4 — Follow the variables.** `rearmedFor` recompute from current variables for
  `until`/`deadline`; scheduler/handler read `deadlineAt`; logs + metric; e2e + JPA + dist.
- [ ] **P5 — UI & tooling & docs.** Process view and graph badge, plugins, guide *"Scheduling and
  deadlines"* (the hotel example end-to-end, including a modified booking), step-types,
  configuration, AI files, skill, TESTING, CHANGELOG.
- [ ] **P6 (optional) — `notBefore`.**

## 6. Open questions (recommendation first)

1. **Does `untilVariable` also follow variable changes?** *Recommend no* — keep its snapshot
   semantics for compatibility; `until` is the new, moving form. Documented side by side.
2. **`ifPast` default.** *Recommend `fire`* — today's behaviour, and "charge now" is the usual answer
   for a late booking. A validation *warning* when `offset` is negative and `ifPast` is not set, so
   the author thinks about it.
3. **A `deadline` already past at start.** *Recommend: time out at once* (then retries are not
   attempted, `onTimeoutStepId` applies) — no separate `ifPast` for deadlines; the on-timeout route
   is exactly that choice.
4. **Zone default.** *Recommend `workflow.time.zone` defaulting to the JVM zone* (no change for
   existing installs); recommend setting it explicitly in the Helm chart values (`UTC` by default
   there, overridable).
5. **Stored clock.** `deadlineAt` stays a server-local `LocalDateTime` (no migration). *Recommend
   keeping it* now; moving the engine to `Instant` is a separate, wider change — noted in
   `.dev/ideas.md`.
