# EventConductor demo — local stack

Runs the **booking-saga demo** end to end on your machine with docker-compose: a reservations app
(shell + booking) driven by an EventConductor workflow, with **Keycloak** login and an **api-gw** in
front. It's the same journey the Playwright e2e exercises (see [`e2e/`](e2e/README.md)).

The engine (orchestrator + forms) runs from the **public 2.0.0 images**; only the demo's own services
(shell, api-gw, booking) are built locally from the `eventconductor` repo, because those images are
private.

```
┌ browser ─────────── http://localhost:8191 (api-gw) ───────────────────────────┐
│                        │           │            │            │                 │
│                     shell        _forms      _workflow     _booking            │
│                    (:8101)     forms:8106  orchestr:8105  booking:8108         │
│                        └── login ── keycloak.local:8080 (Keycloak) ────────────┘
└ postgres:5433  ·  redpanda:9192  ·  DBs: workflow (engine) + demo (booking) ────┘
```

## Prerequisites

- **Docker** (with compose v2). On Apple Silicon the engine images are amd64 → they run emulated
  (first start ~60 s); this is expected.
- Only to (re)build the demo images or run the e2e: **JDK 21** and Maven. This stack lives inside the
  `eventconductor` repo at `demo/local-stack/`, so paths below are relative to it.

## 1. Build the three demo images (one-off)

The demo services ship a prebuilt jar under `target/` and a `Dockerfile.runtime` that just copies it.
From the `eventconductor` repo:

```bash
cd ~/IdeaProjects/eventconductor/demo

# booking worker + api-gw router — build straight from their prebuilt jars
( cd booking-service && docker build -f Dockerfile.runtime -t mateu-demo-booking:local . )
( cd api-gw         && docker build -f Dockerfile.runtime -t mateu-demo-api-gw:local  . )
```

### The shell needs a local Keycloak URL

⚠️ The shell hardcodes its Keycloak login URL at **compile time** (Mateu bakes `@KeycloakSecured(url=…)`
into the generated page), so for a local run it must be rebuilt pointing at `keycloak.local`. Edit
`demo/shell/src/main/java/io/mateu/workflow/shell/infra/in/ui/ShellHome.java`:

```java
@KeycloakSecured(url = "http://keycloak.local:8080", realm = "mateu", clientId = "demo")
```

(Optionally trim the `users`/`content`/`data`/`controlPlane` menus + the control-plane `dashboard`
field — those backends aren't in this stack, so leaving them only shows harmless error toasts.) Then:

```bash
cd ~/IdeaProjects/eventconductor/demo/shell
./mvnw -q -DskipTests package
docker build -f Dockerfile.runtime -t mateu-demo-shell:local .
```

> This local edit is **not committed** — a framework change to make the URL an env var
> (`${KEYCLOAK_URL:…}`) is pending a Mateu release. Until then the local build carries the override.

## 2. Bring the stack up

```bash
cd ~/IdeaProjects/eventconductor/demo/local-stack

./setup.sh                     # one-off: makes wf-repo/ + forms-repo/ local git-import repos
docker compose up -d           # pulls the public engine images, starts everything
docker compose ps              # wait until orchestrator + forms are "healthy" (~1 min emulated)
```

Give it a moment before logging in:

- **Keycloak** imports the realm on first start — it needs ~20–40 s to answer. If the login page
  errors right after `up`, wait a few seconds and retry. Check it's ready with:
  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/realms/mateu   # 200 = ready
  ```
- **orchestrator + forms** run emulated (amd64) → first start ~1 min until `healthy`. They also
  git-import the workflow/form defs on startup, so the first reservation only works once they're up.

## 3. Use it

**One-time host setup (required for browser login).** The shell redirects login to
`http://keycloak.local:8080/…`, and your browser must resolve that name to localhost. Add it to
`/etc/hosts` (once):

```bash
echo "127.0.0.1 keycloak.local" | sudo tee -a /etc/hosts
```

(The Playwright e2e doesn't need this — it maps the name with a Chromium host-resolver rule instead.)

Open **http://localhost:8191** and log in with **`demo` / `demo`**.

The journey:
1. **Booking → Bookings → New** → set a *Lead name* → **Save**. A payment-verification task is raised;
   the header notice flips from *No tasks* to **You have tasks!** (reload to refresh it).
2. Follow the notice → **Run** the task → **Claim** → tick **Payment received** → **Complete**
   → the reservation shows **Confirmed**. (Leave it unticked → **Cancelled**.)
3. Create another and do nothing: after the **30 s** step timeout the notice clears and the
   reservation is **Cancelled** — native `onTimeoutStepId` routing to `cancelar-reserva`.

| URL | what |
|---|---|
| http://localhost:8191 | the app (api-gw entry point) |
| http://localhost:8080 | Keycloak (admin: `admin`/`admin`) |
| http://localhost:8105/8106/8108 | orchestrator / forms / booking direct |

## 4. Run the end-to-end test

Drives the whole journey headless with Playwright-Java. Needs the stack up and the workflow's
`verify-payment` timeout at `30000` (default). See [`e2e/README.md`](e2e/README.md).

```bash
cd e2e && mvn test            # HEADED=1 mvn test to watch it
```

> **Cold start:** the very first run right after `docker compose up` can flake (a reservation that
> should time out ends up confirmed) — the Kafka consumer groups are fresh and start at
> `auto-offset-reset: latest`, so early events cross wires while they stabilize. Re-run once the stack
> is warm and it's deterministic (3/3).

## Layout & internals

- `docker-compose.yml` — the stack. `keycloak.local` is a network alias on the keycloak service so the
  token's `iss` claim matches from both the browser (Playwright host-resolver rule) and the containers.
- `wf-repo/` — git repo of `.ec` workflow defs, git-imported by the orchestrator (`verify-booking-payment`).
  Edit + commit here, then `docker compose restart orchestrator` to re-import.
- `forms-repo/` — git repo of form defs (`verify-payment.yaml`), git-imported by forms.
- `initdb/` — creates the `workflow` (engine) and `demo` (booking) databases.
- `mateu-realm.json` — Keycloak realm (`mateu`), client `demo`, user `demo`/`demo`. `webOrigins` must
  list `http://localhost:8191` explicitly (a wildcard redirect yields no CORS origin). Recreate keycloak
  (`docker compose rm -sf keycloak && docker compose up -d keycloak`) to re-import after editing.

## Reset / teardown

```bash
docker compose down            # stop, keep data
docker compose down -v         # stop and wipe the databases (fresh start)
```
