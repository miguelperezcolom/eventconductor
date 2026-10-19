# Sensitive process variables — encryption plan

Goal: parity with Temporal's Payload Codec for banking/health users — the values of variables a
definition declares sensitive are stored and transported only as ciphertext — while keeping the DSL
the single source of truth (the definition says what is sensitive; configuration only says with which
keys).

The one difference with Temporal is structural and drives the design: EventConductor's server *is* the
interpreter (it evaluates guards, renders HTTP/LLM templates, computes deadlines and correlation keys),
so it must see plaintext in memory. Encryption therefore happens at the **boundaries** of the engine —
every place a value is written to a store or put on a wire — and decryption on the way back in. The
domain model stays plaintext, which means no expression, template or use case has to know about
encryption, and a missed *read* site cannot break a guard; a missed *write* site is a leak, which is
why the write sites are funnelled through a handful of chokepoints and tested raw (columns, outbox
rows, Kafka payloads).

## 1. Declaration (DSL)

```yaml
sensitiveVariables: [cardNumber, "patient.*"]   # names or globs
steps:
  - id: tokenize
    type: ACTION
    outputVariables: [pan, cvv]
    sensitiveOutputs: true                      # this step's outputVariables are sensitive too
  - id: summarize
    type: LLM_CALL
    llm: { prompt: "...${cardNumber}...", allowSensitiveVariables: true }   # explicit opt-in
```

* Patterns: a variable name, or a glob where `*` matches any run of characters and `?` exactly one.
  `patient.*` also matches the variable `patient` itself (a JSON object whose fields are under it).
  Case-sensitive, like variable names. At most 100 patterns, 128 characters each.
* `sensitiveOutputs: true` on a step adds that step's `outputVariables` to the set; it is a violation
  without `outputVariables`.
* Shared rules in `definition-analysis` (`SensitiveVariableRules`): pattern syntax, the step flag,
  **search attributes that read a sensitive variable are refused** (an index of ciphertext is useless
  and an index of plaintext is a leak), **LLM_CALL templates that read a sensitive variable are refused
  unless `llm.allowSensitiveVariables: true`**. The engine (`WorkflowDefinition.checkInvariants`) and
  the Maven plugin (`SpecValidator`) run the same code.
* **Part of the version hash: yes.** The set rides in each process's definition snapshot and decides
  how that process's rows are written; a version must say exactly what its instances protect, and
  turning protection on/off is a change in how instances are stored, so it mints a new version (only
  written to the hash when present, so existing definitions do not re-version). `sensitiveOutputs`
  rides inside the serialized steps (NON_NULL), with the same effect.

## 2. Codec SPI and envelope (module `shared`, package `io.mateu.workflow.crypto`)

* `VariableCodec` — the SPI: `activeKeyId()`, `encode(keyId, plaintext, aad)`, `decode(keyId,
  ciphertext, aad)`, `knows(keyId)`. Bytes in, bytes out.
* Envelope: `ec:enc:v1:<keyId>:<base64url(iv ‖ ciphertext ‖ tag)>` — self-describing, so a reader
  never needs the policy to decrypt: anything that starts with `ec:enc:v1:` is opened, everything else
  is passed through. The header (`ec:enc:v1:<keyId>`) is the AEAD associated data, so the key id cannot
  be swapped. Ciphertexts are *not* bound to the variable name, deliberately: a codec-less worker may
  pass a ciphertext through to another output variable.
* Default `AesGcmVariableCodec`: AES-256-GCM, 96-bit random IV per value, 128-bit tag.
* Keys: `workflow.encryption.keys.<id>=<base64 32 bytes>`, `workflow.encryption.active-key=<id>`
  (optional when exactly one key is configured). **Rotation**: encrypt with the active key, decrypt with
  any configured key; add the new key, switch `active-key`, keep the old one until nothing references
  it (runbook in the guide).
* KMS/Vault hook: `EncryptionKeySource` (keys + active id, e.g. data keys unwrapped from Vault/KMS at
  startup) or a whole `VariableCodec` bean (e.g. KMS Encrypt/Decrypt per value). No cloud SDK
  dependency; the guide shows both.
* `VariableEncryption` — the facade every module uses (`seal`, `open`, `enabled`), auto-configured in
  `shared` (so the engine, forms engine and workers get the same one from the same properties).
  Decryption failures raise `VariableDecryptionException`, whose message names the key id and never a
  value.
* Off unless keys are configured. **Declaring sensitive variables without keys fails at definition load**
  (`WorkflowDefinitionValidator`: "declares sensitiveVariables … but no encryption key is configured
  (workflow.encryption.keys.<id>)") — that covers startup (classpath/directory import runs at startup)
  and every later import (git, REST, UI). An engine whose store already holds such a definition fails
  startup as well (`SensitiveVariablesStartupCheck`), so removing the keys cannot silently turn
  encryption off.

## 3. Policy resolution (engine)

`SensitiveVariablePolicies` → a `SensitiveNames` predicate:
* of a process — from its definition snapshot (cached per definition id/version/hash, like
  `SearchAttributeEvaluator`), registered by process id whenever a process is read or saved;
* of a process id — the registered entry, else the process is loaded once;
* of a definition id — the union over its versions (creation requests, before a process exists);
* global — the union of every definition (messages, which correlate to processes not known yet).
When a process's set is non-empty, the engine's derived carriers are sensitive too: the rendered
`__http` / `__llm` requests (they embed rendered values).

## 4. Where values are encrypted

| Where | How |
|---|---|
| `process_entity.variables` | per variable (`value` sealed, `name` clear) |
| `process_entity.reply_json` (sync replies) | whole blob when the process has sensitive variables |
| `step_execution_entity.variables` | per variable |
| `step_execution_entity.scope_json` / `output_json` (forEach) | whole blob when sensitive |
| `outbox_message_entity.payload` | every `{name, value}` in the event, by the process's set; opened by the relay |
| Kafka (`upstream*`, task topics, `messages`, downstream) | every `{name, value}` in the event before `StreamBridge`; opened by `ProcessUpstreamEventUseCase` on the way in |
| HTTP task queue (`http_task`) / long-poll leases | sealed at rest and as leased: HTTP workers get ciphertext unless they share the keys |
| Forms engine `form_execution.variables` | every value sealed when keys are configured (it does not know the policy; it is a worker) |
| Batch `SET_VARIABLES` parameters | values whose names any definition declares sensitive |
| Process log / audit lines | sensitive values are never quoted (`••••`), and any occurrence of a current sensitive value in a log line is redacted at the `LogMessageRepository` chokepoint |
| Process index, analytics, metrics, search attributes | carry no variable values (verified); search attributes over sensitive variables are refused |
| Retention purge | deletes rows; nothing copied |
| `SetProcessVariables`, `SET_VARIABLES` batches, `CompleteStepManually`, message correlation, child processes | go through the repositories, so they are sealed like any other write |

Memory persistence holds plaintext *in the heap* (there is no "at rest"); its transport boundaries
(Kafka mode) are sealed the same way.

## 5. Workers — the trust model

* **Embedded workers** (in-process `EmbeddedTaskExecutor`) receive plaintext: they are the engine's
  own process, same trust boundary.
* **Kafka and HTTP workers** receive ciphertext for sensitive variables. A worker that must read them
  shares the codec: `worker-api` opens inputs before binding and re-seals outputs whose names arrived
  sealed or match `eventconductor.worker.encryption.sensitive-variables` (on by default whenever
  `workflow.encryption.keys.*` are configured in the worker; `eventconductor.worker.encryption.enabled=false`
  turns it off). A worker without keys sees `ec:enc:v1:…` strings and can pass them through untouched.
* The built-in HTTP_CALL / LLM_CALL workers in their standalone apps need the keys when a definition
  that declares sensitive variables uses them (their rendered request is sealed).

## 6. Expressions, templates, LLM

Guards, payload/HTTP templates, deadlines, correlation and lock keys see plaintext inside the engine.
LLM_CALL may **not** receive sensitive values unless the step opts in (`llm.allowSensitiveVariables:
true`): refused at load when a template names one, and at runtime sensitive variables are left out of
the prompt context of a step that has not opted in.

## 7. UI / REST / MCP

* Values of sensitive variables are masked (`••••`) in REST (`GET /processes/{id}`, `/variables`),
  MCP (`getProcess…`) and the console (variables, step variables, copy-state), for everyone except a
  caller holding one of `workflow.security.sensitive.reveal-roles` (empty by default: nobody).
* Reveal is an explicit, audited action: `POST /workflow/api/processes/{id}/variables/{name}/reveal`
  (reason required), MCP `revealProcessVariable`, and the console "Reveal" action — each writes an
  operator audit line (`[reveal-variable] by <subject>: cardNumber — reason: …`) and counts on
  `eventconductor.processes.operations{operation=reveal-variable}`; a caller without the role gets 403
  and the refusal is logged.
* Writes (set variables, complete step) accept plaintext from authorized operators; the audit line never
  quotes a sensitive value.

## 8. Logs

The engine never logs variable values at INFO/WARN; audit lines and the process log mask sensitive
values; exceptions raised by the codec carry key ids, never values.

## 8b. Digests and derived keys

The synchronous invocation's request fingerprint is an HMAC-SHA256 under a key derived (HKDF) from the
active encryption key, `h1:<keyId>:<base64url>`, verified under the key it names (rotation-safe); legacy
plain SHA-256 fingerprints still verify. Correlation and lock keys (and `processLock.key`) that read a
sensitive variable are refused at load, like search attributes: they are stored in clear.

## 9. Out of scope / deferred

* Schedules and event triggers store *templates/initial values configured by an operator*, not process
  data; they are not encrypted (documented: do not put secrets in them).
* A child process stores what its own definition declares: declare the same names in the child.
* Python worker SDK decoding (envelope documented so it can be implemented).
* Instance migration (#551, unmerged): it rewrites processes through the repositories, so values stay
  sealed; re-check the variable mapping when it lands.
* Re-encryption job for rotation (rows are re-sealed with the active key on their next write; the
  runbook says how long to keep old keys).

## 10. Tests

Codec (round trip, tamper, wrong key, rotation, envelope parsing), rules (patterns, step flag, search
attribute and LLM refusals), policy resolution, JSON sealing, e2e memory + JPA (raw column ciphertext
on H2 and PostgreSQL Testcontainers, outbox row ciphertext, embedded worker gets plaintext, guard on a
sensitive variable works), Kafka publisher payload sealed, masking + audited reveal in REST/MCP/UI, log
redaction, validation errors (no keys, search attribute, LLM), worker-api open/re-seal, forms at rest,
test kit compatibility.
