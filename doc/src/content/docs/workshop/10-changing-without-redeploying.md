---
title: "10 · Changing without redeploying"
description: Keep the definitions in a Git repository the engine imports, ship a change by merging, and watch the old and new versions run side by side.
---

The workshop app loads its definitions from its own classpath, so changing one means rebuilding and restarting the app. In production you rarely want that: the business process changes more often than the code around it. The engine can **import definitions from a Git repository** instead, and re-import it on a webhook — so a change ships by **merging a pull request**, not by redeploying a service.

## A repository of definitions

For the workshop, a local repository will do:

```bash
mkdir ~/hotel-definitions && cd ~/hotel-definitions && git init -b main
cp …/examples/workshop-hotel-booking/src/main/resources/workflows/06-hotel-booking.yaml hotel-booking.yaml
git add . && git commit -m "hotel-booking v1"
```

Start the app pointing at it:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="\
  --workflow.git-import.repositories[0].url=file://$HOME/hotel-definitions \
  --workflow.git-import.repositories[0].branch=main"
```

```text
WorkflowDefinitionGitImportRunner : Starting workflow definition import from 1 Git repository/ies…
WorkflowDefinitionGitImportRunner : Imported 1 workflow definition(s): [Hotel booking [hotel-booking]]
```

In production the URL is your definitions repository (HTTPS with a token, configured as a secret), and the configuration is `workflow.git-import.*` — see [Importing from Git](/guides/workflow-definitions/#importing-from-git).

## A booking on the current version

```bash
scripts/start.sh hotel-booking BOOKING-8 '{"guest": "Gus", "rooms": [
  {"number": "201", "type": "double"}, {"number": "202", "type": "double"}]}'
```

It waits for the manager's approval — a booking that will still be running when the definition changes.

## Change the process

The hotel wants the assistant to draft the confirmation email, as in module 5. In the repository, add one step and re-point `notify-guest` at it:

```yaml
- id: draft-confirmation                 # v2: the assistant drafts the email
  type: LLM_CALL
  name: Draft the confirmation email
  preconditionStepId: rooms
  llm:
    connection: assistant
    prompt: "Write a short, warm confirmation email to ${guest}."
    outputVariable: confirmationEmail

- id: notify-guest
  type: ACTION
  name: Tell the guest
  preconditionStepId: draft-confirmation
```

```bash
git commit -am "hotel-booking v2: draft the confirmation email"
```

## Ship it: the webhook

The engine re-imports when the repository's webhook calls it. For the local repository, call it yourself:

```bash
curl -X POST http://localhost:8095/workflow/webhooks/generic -H 'Content-Type: application/json' -d '{}'
```

```text
GitImportWebhookController         : Webhook (GENERIC) triggered: importing 1 repository/ies…
WorkflowDefinitionVersioningService : Recorded workflow definition 'hotel-booking' version 3 (content changed)
GitImportWebhookController         : Webhook import: 1 definition(s) imported: [Hotel booking [hotel-booking]]
```

No build, no restart. With GitHub, GitLab or Bitbucket, the provider calls `/workflow/webhooks/{provider}` on every push, signed with `workflow.git-import.webhook-secret` — an unsigned webhook is refused outside a no-login workshop like this one. See [Git webhook](/guides/workflow-definitions/#git-webhook).

Start a new booking: it runs the new version.

```bash
scripts/start.sh hotel-booking BOOKING-9 '{"guest": "Gus", "rooms": [{"number": "203", "type": "double"}]}'
```

## Two versions, side by side

**Workflow → Definitions → Hotel booking** shows the new step:

![The definition, now with the Draft the confirmation email step; two runs waiting at the approval](/workshop/10-definition-v3.jpg)

and its **Versions** tab, every version with the processes that run, or ran, on it:

![Versions: BOOKING-8 on v2, BOOKING-9 on v3](/workshop/10-versions.jpg)

BOOKING-8 kept the version it started on — it finished on v2 once its approval was decided — while BOOKING-9 runs the new one. Nothing was interrupted. (The extra row is an artefact of this setup: the app imported the same file twice, once from its classpath and once from Git.)

To move the older bookings onto the new version — so they too draft the email — use **Migrate instances…** on the definition, or **Migrate…** on one run. `check-migration` in CI has already told you whether that is safe: see [Part 9 · Automate it](/workshop/testing/automate/) and [Migrating running processes](/guides/migrating-running-processes/).

## The whole loop

1. Edit the definition — in the [IDE plugins](/guides/ide-plugins/)' graph editor or as text, validated as you type.
2. Open a pull request: CI validates, runs the workflow tests and checks the migration.
3. Merge: the webhook imports it; new processes run the new version; running ones keep theirs or are migrated.

That is the end of the workshop. You have built, broken, operated, debugged, tested and changed a real process — with about a hundred lines of definitions and four small handlers.
