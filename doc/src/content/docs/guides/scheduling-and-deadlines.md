---
title: Scheduling and Deadlines
description: Wait for, or finish by, a moment computed from the process's own data — "3 days before check-in, at 09:00 hotel time" — that follows the data when it changes.
---

Business processes are full of dates: *charge the balance **3 days before check-in***, *the payment must arrive **by the day before check-in***, *remind the guest **the evening before***. EventConductor lets a definition say that directly, with a **moment** computed from the process's variables.

## A moment

```yaml
until:                         # a TIMER waits for it; any waiting step can have a `deadline`
  date: "${checkinDate}"       # a template → ISO date, date-time or offset date-time
  offset: -P3D                 # optional ISO-8601 offset, may be negative
  at: "09:00"                  # optional time of day, set after the offset
  zone: "${hotelZone}"         # optional IANA zone (or a template); default workflow.time.zone
```

Or, for the date alone: `until: "${checkinDate}"`.

It is resolved in this order:

1. `date` is rendered ([payload template](/guides/payload-templates/) syntax) and read: an offset date-time (`2026-08-01T15:00:00+02:00`) keeps its instant; a date-time or a date (its start of day) is read **in `zone`**.
2. `offset` is added in `zone`. Days, weeks, months and years are **calendar** arithmetic — `-P3D` is three calendar days back, the same wall-clock time even across a daylight-saving change; `PT…` hours and minutes are elapsed time.
3. `at` replaces the time of day (a time that does not exist on a DST gap moves forward).

`zone` defaults to **`workflow.time.zone`**, which defaults to the JVM's zone. Set it when your servers do not run in your business's zone — a cluster in UTC booking hotels in Madrid.

## Waiting for a moment: `TIMER` + `until`

```yaml
- id: wait-balance
  type: TIMER
  name: 3 days before check-in
  preconditionStepId: confirm-booking
  until: { date: "${checkinDate}", offset: -P3D, at: "09:00", zone: "${hotelZone}" }
- id: charge-balance
  type: HTTP_CALL
  preconditionStepId: wait-balance
  http: { connection: payments, method: POST, path: "/charges", body: { booking: "${bookingId}" } }
```

The timer is durable like any other: it survives restarts, and pausing the process does not move an absolute moment.

### When the moment has already passed

A booking made two days before check-in is already past "3 days before". `ifPast` says what then:

| `ifPast` | when the moment is already past as the timer starts |
|---|---|
| `fire` (default) | the timer completes at once — charge now |
| `timeout` | the timer ends `TIMEOUT`: it follows the step's **`onTimeoutStepId`** if it has one, and fails the process (compensating) if not. No retries — the moment will not come back |

```yaml
- id: wait-balance
  type: TIMER
  until: { date: "${checkinDate}", offset: -P3D, ifPast: timeout }
  onTimeoutStepId: charge-now           # the late-booking route
```

## Finishing by a moment: `deadline`

Any step that waits — `ACTION`, `USER_TASK`, `RULE`, `WAIT_FOR_MESSAGE`, `PROCESS`, `HTTP_CALL` — can have a `deadline`, a moment by which it must have finished:

```yaml
- id: wait-payment
  type: WAIT_FOR_MESSAGE
  messageName: payment-received
  correlationExpression: bookingId
  deadline: { date: "${checkinDate}", offset: -P1D, at: "12:00", zone: "${hotelZone}" }
  onTimeoutStepId: cancel-booking
```

- Reaching it is a **timeout**: `onTimeoutStepId` if set, else the step fails (and the saga compensates).
- With a `timeout` as well, **the earlier wins**.
- Unlike `timeout`, a deadline **does not restart with a retry** — it is a business moment, not a per-attempt budget. A step that reaches its deadline is not retried.
- A deadline already past when the step starts times it out on the scheduler's next tick.

## When the date changes

Guests move their check-in. A waiting `until` timer, and a waiting step's `deadline`, **follow the process's variables**: whenever the variables change — a worker's output, a correlated message, a form submission — the moment is recomputed, and the process log says so (*"Timer of step … moved with the process's data: now due … (was …)"*).

- Moved **later**: the timer waits longer.
- Moved **earlier but still ahead**: it fires earlier.
- Moved **into the past**: with `fire` it fires on the next tick; with `ifPast: timeout` it times out.
- If the date **disappears** (the variable is cleared), the moment already armed is kept — a waiting step is never silently disarmed.

The usual shape is a parallel branch that listens for the change:

```yaml
- { id: fork, type: FORK, preconditionStepId: confirm-booking }
- id: wait-balance
  type: TIMER
  preconditionStepId: fork
  until: { date: "${checkinDate}", offset: -P3D, at: "09:00", zone: "${hotelZone}" }
- id: booking-modified
  type: WAIT_FOR_MESSAGE
  preconditionStepId: fork
  messageName: booking-modified          # carries the new checkinDate as a message variable
  correlationExpression: bookingId
```

`untilVariable` and `duration` keep their original meaning: computed once, from the variables as the step started.

## Validation

Moments are checked at build time by the [Maven plugin](/reference/maven-plugin/) and on import: the offset, the time of day and a literal zone must parse, the templates in `date` and `zone` must parse, `until` only on a `TIMER` (exclusive with `duration` and `untilVariable`), `deadline` only on a step that waits, `ifPast` only on `until`. A date that cannot be resolved at run time fails the timer (`ERROR`), like an unreadable `untilVariable`.
