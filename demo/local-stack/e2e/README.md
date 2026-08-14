# Booking-journey E2E (Playwright-Java)

End-to-end test of the EventConductor booking-saga demo, driving the real UI a human operator uses.
It exercises the full journey through the running docker-compose stack:

1. **Log in** through Keycloak.
2. **Create a reservation** → a payment-verification task is raised and the header notice lights up
   (`No tasks` → `You have tasks!`).
3. Follow the notice to the **task list**, **claim** the task and **complete** it:
   - *payment received* (checkbox ticked) → the reservation ends **Confirmed**;
   - *payment not received* (checkbox left unticked) → the reservation ends **Cancelled**.
4. Create a reservation and **do nothing**: after the 30 s step timeout the notice clears and the
   reservation is **Cancelled** — native `onTimeoutStepId` routing to `cancelar-reserva`, no FORK/TIMER.

One `@Test` per outcome (`paymentReceived_*`, `paymentNotReceived_*`, `noAction_timesOut_*`), run in
order, sharing one logged-in browser. `@BeforeAll` drains any pre-existing pending tasks so the global
header notice is deterministic.

## Prerequisites

- The full stack up: `docker compose up -d` in the parent dir (`..`). Services needed:
  postgres, redpanda, orchestrator, forms, booking, keycloak, shell, api-gw.
- The `verify-booking-payment` workflow's `verify-payment` step must have `timeout: 30000` (matches
  `WF_TIMEOUT_MS`). See `../wf-repo/verify-booking-payment.ec`.
- Playwright's Chromium (downloaded automatically by the driver on first `mvn test`).

## Run

```bash
mvn test                 # headless
HEADED=1 mvn test        # watch it in a real browser window
```

Config via env (defaults in parentheses): `BASE_URL` (http://localhost:8191), `KC_USER` (demo),
`KC_PASS` (demo), `WF_TIMEOUT_MS` (30000).

## How the Keycloak hostname resolves on both sides

The browser and the containers must agree on Keycloak's hostname so the token's `iss` claim matches.
Both use `keycloak.local:8080`:

- **Browser**: the test launches Chromium with `--host-resolver-rules=MAP keycloak.local 127.0.0.1`.
- **Containers**: the `keycloak` service has a `keycloak.local` network alias; `KC_HOSTNAME` is set to
  `http://keycloak.local:8080`, so the issuer it stamps is reachable from the shell/api-gw too.

## Notes / gotchas

- The header task notice renders in a **shadow root**, so `document.body.innerText` can't see it — use
  Playwright locators (`getByText`), which pierce shadow DOM.
- Mateu mirrors many strings into an `aria-live` announcer `<div>` as well as the real component, so
  text matches use **exact + first** to avoid strict-mode violations.
- The booking status updates **asynchronously** (task completion → Kafka → booking worker → read
  model); the status read reloads and polls until the row leaves `Pending`.
- The Bookings grid loads rows lazily and has a leading (blank) selection column, so the status is
  read as the first known status value after the guest's name cell, not by a fixed column offset.
