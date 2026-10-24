---
title: Sensitive data and encryption
description: Declare which process variables are sensitive and EventConductor stores and transports them only encrypted (AES-256-GCM, rotatable keys, a codec SPI for KMS/Vault), masks them in the console, REST and MCP, and reveals one only on an audited request.
---

Card numbers, national ids, diagnoses: some process variables must never sit in a database, a Kafka topic or a log line in clear. EventConductor lets a definition say which ones they are, and then every place the engine **writes** a value — the process and step tables, the outbox, the broker, the HTTP task queue, the process log — holds only ciphertext, while the engine itself (guards, templates, deadlines, embedded workers) keeps working on the plaintext. It is the counterpart of Temporal's Payload Codec, declared in the definition rather than wired into every client.

## Declaring sensitive variables

```yaml
id: card-payment
name: Card payment
sensitiveVariables: [cardNumber, "patient.*"]   # names or globs
steps:
  - id: start
    type: START
    name: Start
  - id: tokenize
    type: ACTION
    name: Tokenize the card
    topic: payments
    preconditionStepId: start
    outputVariables: [pan, cvv]
    sensitiveOutputs: true                      # this step's outputs are sensitive too
  - id: summarize
    type: LLM_CALL
    name: Summarize the case
    preconditionStepId: tokenize
    llm:
      connection: claude
      prompt: "Summarise the case of ${patient.name}"
      outputVariable: summary
      allowSensitiveVariables: true             # explicit opt-in, see below
```

* **`sensitiveVariables`** — variable names, or globs: `*` matches any run of characters (dots included), `?` exactly one. `patient.*` also covers the variable `patient` itself — the one whose value holds those fields. Case-sensitive, like variable names; at most 100 patterns.
* **`sensitiveOutputs: true`** on a step adds that step's `outputVariables` to the set (a worker step lists there the names it returns). It is an error without `outputVariables`.
* Both are **part of the definition's version**: turning protection on or off mints a new version, and every process protects what the version it runs declares (it rides in the process's definition snapshot). A definition that declares nothing hashes exactly as before.

The same rules run in the engine at load and in the [Maven plugin](/reference/maven-plugin/) at build time (`SensitiveVariableRules`), so a definition the build accepts is one the engine protects. Two more rules come with them:

* **A search attribute may not read a sensitive variable.** An index is plaintext by nature, and an index over ciphertext could find nothing — `searchAttributes: { card: "${cardNumber}" }` is refused.
* **A correlation or lock key may not read a sensitive variable** — a step's `correlationExpression`, `lockKey`, `onEvent.correlationKey`, `awaitReply` correlation, or the definition's `processLock.key`. A key is stored in clear and looked up by equality (the waiting step, the lock row, the event receipt), so it would be the sensitive value at rest under another name.
* **An LLM_CALL may not put a sensitive variable in its prompt** unless the step opts in with `llm.allowSensitiveVariables: true` — the prompt leaves your trust boundary for the model provider. A prompt naming one is refused at load; without the opt-in the engine also leaves sensitive variables out of the prompt's context, so they cannot reach it indirectly.
* **An AGENT may not put a sensitive variable in its `taskPrompt` or `input`** (a string anywhere in the input counts) unless the step opts in with `agent.allowSensitiveVariables: true` — what the agent gets leaves the engine for the agent runtime, and through it a model and its tools. Refused at load and at build alike; without the opt-in sensitive variables are also left out of the request's context. With it, the rendered request (`__agent`) is sealed in the step's row, the outbox and the `agent-calls` message, like `__http` and `__llm`.

## Keys

Encryption is off until a key is configured, and **a definition that declares sensitive variables is refused while no key is configured** — at startup for the definitions on the classpath or in the import directory, at import for git, REST and the console. An engine whose database already holds such a definition refuses to start without keys (the "someone removed the keys" case), because it would write new values in clear and could not read the stored ones. Should the database be unreachable at boot (the engine starts anyway and resumes when it returns), that check is skipped — and every write of a sensitive variable without a key fails instead of storing it in clear.

```properties
# base64 of 32 random bytes: openssl rand -base64 32
workflow.encryption.keys.2026-10=${EC_KEY_2026_10}
workflow.encryption.active-key=2026-10        # optional with a single key
```

The default codec is **AES-256-GCM**: a fresh 96-bit random IV per value, a 128-bit authentication tag, so a tampered value fails to open rather than decrypting to garbage. A sealed value is stored as a self-describing envelope:

```
ec:enc:v1:<keyId>:<base64url(iv ‖ ciphertext ‖ tag)>
```

The header (`ec:enc:v1:<keyId>`) is authenticated with the value, so the key id cannot be swapped. Anything that starts with `ec:enc:v1:` is opened with the key it names; anything else is passed through — no reader needs the policy to open a value. Ciphertexts are deliberately *not* bound to the variable name, so a worker without keys can pass one through to another output variable.

### Key rotation runbook

1. Generate the new key and add it **next to** the current one; keep `active-key` on the current key. Roll the change out to every engine, forms engine and worker that holds keys — they must all be able to *open* the new key before anyone *seals* with it.
2. Switch `workflow.encryption.active-key` to the new key and roll out again. From now on every value written is sealed with it; values already stored keep their old key id and keep opening with the old key.
3. Keep the old key until nothing references it. A row is re-sealed with the active key the next time it is written, so a process that is still running moves to the new key at its next step; a finished one keeps the old key until the [retention purge](/guides/workflow-definitions/#process-retention) deletes it. Count what is left with SQL, e.g. `select count(*) from process_entity where variables like '%ec:enc:v1:2026-04:%'` (and the same over `step_execution_entity.variables` and `outbox_message_entity.payload`).
4. Remove the old key. A value sealed with a key that is no longer configured fails to open with an error naming the key id (never the value) — the process cannot be read until the key is back.

Never reuse a key id for different key material: values sealed under the old material would fail authentication.

### KMS, Vault: the hooks

No cloud SDK is a dependency. Two extension points cover a key manager:

* **`EncryptionKeySource`** — where the default AES-GCM codec gets its keys. Declare a bean to fetch data keys from Vault's KV store, or unwrap them with AWS KMS / GCP KMS / Azure Key Vault at startup:

  ```java
  @Bean
  EncryptionKeySource vaultKeys(VaultTemplate vault) {
      var data = vault.read("secret/eventconductor/variable-keys").getData();
      return EncryptionKeySource.of((String) data.get("active"), Map.of(
              "2026-10", Base64.getDecoder().decode((String) data.get("2026-10")),
              "2026-04", Base64.getDecoder().decode((String) data.get("2026-04"))));
  }
  ```

* **`VariableCodec`** — the SPI itself: `encode(keyId, plaintext, associatedData)` / `decode(...)` over bytes, `activeKeyId()`, `knows(keyId)`. Declare a bean to replace AES-GCM entirely — a KMS `Encrypt`/`Decrypt` call or a Vault transit request per value — and the engine keeps wrapping its output in the same envelope. An implementation must authenticate (a tampered value must fail), be thread-safe, and never put plaintext or key material in an exception message.

The engine, the forms engine and the workers all build their codec the same way (`VariableEncryptionAutoConfiguration` in the `shared` module), from the same properties or beans.

## What is encrypted, where

| Where | What is stored or sent |
|---|---|
| `process_entity.variables` | each sensitive variable's value sealed; the names, and every other value, as before |
| `process_entity.reply_json` (a synchronous reply) | the whole payload sealed when the process has sensitive variables |
| `step_execution_entity.variables` | each sensitive value sealed — including the rendered `__http` / `__llm` requests, which embed rendered values |
| `step_execution_entity.scope_json` / `output_json` (forEach) | sealed whole when the process has sensitive variables |
| `outbox_message_entity.payload` | every `{name, value}` of the event sealed by the process's policy; the relay opens them before delivery |
| Kafka (`upstream*`, task topics, `messages`, downstream, dead letters) | every `{name, value}` of the message sealed before it reaches the broker; the engine opens what comes back in one place, before any handler sees it |
| HTTP task queue (`http_task`) and its leases | sealed: an HTTP worker gets ciphertext unless it has the keys |
| Forms engine (`form_execution.variables`) | every value sealed whenever the forms engine has keys (it is a worker and does not know the policy) |
| Batch `SET_VARIABLES` parameters | values whose names any definition declares sensitive |
| Process log, audit trail | never the value: see [Logs](#logs) |
| Process index, analytics, metrics, search attributes | carry no variable values; search attributes over sensitive variables are refused |

Everything that changes variables — a worker's answer, a message, `SetProcessVariables`, a `SET_VARIABLES` batch, a manual step completion, a child process's result — goes through the same repositories, so it is sealed the same way. The retention purge deletes rows and copies nothing.

With `workflow.persistence=memory` there is no "at rest": the processes live in the JVM's heap, in clear, like everything else the engine holds in memory. The transport boundaries (Kafka mode) are sealed the same way.

## Inside the engine: plaintext

The engine is the interpreter of your workflows: guards, payload and HTTP templates, deadlines, correlation and lock keys, `forEach` items — all of them read variables. They see **plaintext**, in memory, exactly as before. Encryption is a property of what the engine writes and sends, never of what a workflow can compute with. In particular an `HTTP_CALL` renders sensitive values into its request when its templates name them: the call is the point (a payment gateway needs the card number). The rendered request is then sealed in the step's row and on its way to the HTTP-call worker. The same goes for a `PUBLISH_EVENT` whose `data` template names a sensitive variable: the CloudEvent is a message to another system and carries what the template renders — name only what the receiver may see. (`SEND_MESSAGE`, which talks to processes, is sealed like any other message.)

## Workers: the trust model

* **Embedded workers** (`EmbeddedTaskExecutor`, in the engine's JVM) receive plaintext. They are the engine's own process — the same trust boundary.
* **Kafka and HTTP workers** receive the sensitive variables **sealed**. A worker that needs them shares the keys: give it the same `workflow.encryption.*` configuration (or `EncryptionKeySource` / `VariableCodec` bean) and `worker-api` does the rest — it opens the inputs before your handler binds them, and seals again every output named like an input that arrived sealed, plus the names (or globs) in `eventconductor.worker.encryption.sensitive-variables`. `eventconductor.worker.encryption.enabled=false` hands the sealed strings to the handler instead.
* **A worker without keys** sees `ec:enc:v1:…` strings. It can store or forward them untouched — the engine opens them when they come back — but it cannot read them. That is the point: grant keys only to the workers that must see the data.
* The **built-in HTTP_CALL and LLM_CALL workers** run inside the orchestrator by default, which already has the engine's `workflow.encryption.*` configuration. When they run in their own apps (the optional `http-worker-standalone-app`, `llm-worker-standalone-app`), those need the keys if a definition with sensitive variables uses them: their rendered request is sealed. So does the **agent runtime** that serves `AGENT` steps: `__agent` is sealed whenever the process declares sensitive variables.
* Values a worker returns **without** sealing them (a worker that does not know they are sensitive) travel back in clear on its reply and are sealed by the engine at rest. Configure `sensitive-variables` in the worker if that hop matters to you.
* The **Python worker SDK** does not open sealed values yet; the envelope above is the whole format if you need to implement it.

## The console, REST and MCP: masked, revealed on request

Every view of a process shows a sensitive value as `••••` — the console (variables, step variables on the diagram, the reply tab, "copy state"), `GET /workflow/api/processes/{id}` and `/variables`, the MCP `getProcess…` tools — for everyone. Reading one is an explicit action:

* `POST /workflow/api/processes/{id}/variables/{name}/reveal` with `{"reason": "…"}`, the MCP tool `revealProcessVariable`, or **Reveal variable** in the process detail.
* It needs one of the roles in `workflow.security.sensitive.reveal-roles` (none by default: nobody can reveal), on top of the usual access to the process (flow authorization). An API key alone identifies no one and can never reveal.
* Every reveal, and every refusal, writes an operator line in the process's audit trail — `[reveal-variable] by alice: cardNumber — reason: chargeback CB-9` — and counts on `eventconductor.processes.operations{operation=reveal-variable}`. The line names the variable, never its value.

Writing is unchanged: an operator with the right to change variables can set a sensitive one, and the audit line says `set cardNumber = •••• (was ••••)`.

A synchronous invocation's reply is the payload the workflow chose to return to its caller: the caller gets it in clear (it is stored sealed). Don't put a sensitive variable in `replyVariables` unless the caller is meant to have it.

## Logs

* The engine never logs a variable value: the lines that used to print whole events print them with every sensitive (and every sealed) value masked.
* The **process log** — what steps, workers and the engine write about a process, shown in its Errors and Messages tabs and over REST/MCP — is redacted where it is stored: every occurrence of one of the process's current sensitive values (4 characters or more) is replaced by `••••`, and so is any sealed value. A worker failing with "card 4111… declined" is recorded as "card •••• declined".
* Codec errors name the key id and what went wrong, never a value.

## Migrating to a version that changes them

A [migration](/guides/migrating-running-processes/#sensitive-variables) re-encodes a process as its target declares: values that become sensitive are encrypted in the process row and every step row, values that stop being sensitive are stored in clear, and the dry run and `check-migration` warn about both. A target declaring sensitive variables is refused without keys; values set by the migration's `variableMapping` are sealed on the wire, in a MIGRATE batch and at rest, and masked in its audit line.

## Fingerprints

A synchronous invocation stores a fingerprint of its request (business key and variables) so a retry with the same idempotency key can be told apart from a different request reusing it. A plain SHA-256 of a request made of a card number could be reversed by enumerating card numbers, so with keys configured the fingerprint is an **HMAC-SHA256** under a key derived from the active encryption key (HKDF-SHA256, fixed label `eventconductor/fingerprint/v1` — a separate key, rotated with it), stored as `h1:<keyId>:<base64url>`. A retry is verified under the key the stored fingerprint names, so a rotation does not break idempotency for invocations still within their retention — keep the old key until they expire. Without keys it is the plain digest it always was; a custom `VariableCodec` provides fingerprint keys through `fingerprintKey(keyId)` (a KMS-backed codec that returns none falls back to the plain digest).

No other stored value is a digest of variables: search attributes and correlation/lock keys over sensitive variables are refused at load, event receipts hold what the event carried (business key, correlation key — never process variables), and the process log is redacted.

## Known limits

* Schedules and event triggers hold *configuration* — initial variables and templates an operator typed — not process data; they are not encrypted. Do not put secrets in them.
* A child process stores what **its own** definition declares: declare the same names in the child definition.
* There is no background re-encryption job: rows move to the active key when they are next written (see the rotation runbook).

## Configuration reference

| Property | Default | Meaning |
|---|---|---|
| `workflow.encryption.keys.<id>` | — | A key: the base64 of 32 bytes. Several for rotation; ids are 1–64 letters, digits, `_`, `.`, `-` |
| `workflow.encryption.active-key` | the only key | The key new values are sealed with; required with several keys |
| `workflow.security.sensitive.reveal-roles` | — (nobody) | Roles allowed to reveal a sensitive variable (audited) |
| `eventconductor.worker.encryption.enabled` | `true` | A worker with keys opens sealed inputs and re-seals its outputs |
| `eventconductor.worker.encryption.sensitive-variables` | — | Output names (or globs) a worker seals even when no input of that name came sealed |
