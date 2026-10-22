# IDE execution validation

The implementation is on `feat/vscode-workflow-execution`.
The extension is version `0.2.3`. The backend needs migration V58 and explicit configuration
`workflow.test-runs.enabled=true`. No Marketplace publication or external engine deployment
was performed.

## Automated evidence

- Initial execution implementation: 53 passing Node tests (the final 0.2.3 suite has 78). Real loopback HTTP transport tests cover payloads,
  authentication headers, redirect refusal, response limits, invalid responses, timeout and
  cancellation. Session tests cover continuation versions/history, filtering and pagination,
  serialized polling, stale responses, visibility/disposal, missing history, forbidden child
  pages, circular links and a continuation not visible yet. Host tests cover unsaved edits,
  workspace trust, immutable submission retries, credential isolation and closing during POST.
- `WorkflowExecutionControllerTest`: real MVC request binding, schema validation, authorization,
  graph projection and test-run service, with an in-memory store and a mocked creator. Covers
  opt-in access, replay/conflict, anonymous access, the mandatory test-run role (also with flow
  authorization off), private `vscode-<id>-<runId>` IDs, refused migration, snapshot permissions,
  masking, injected nodes, omission of prompts/inputs and paginated source-execution relations.
- `IdeExecutionPersistenceTest`: V58 and JPA mappings on migrated H2, retained test metadata,
  direct-child SQL join, pagination/filtering, and normal processes defaulting to non-test.
- `TestRunMutexTest`: competing submitters cannot enter creation together before the process
  exists, and failures release the submission lock. Production wiring reuses the engine's
  named distributed locks and transactional process lock.
- `IdeTestRunsTest`: the real embedded engine, synthetic task/model/agent stubs and a test clock.
  An unpublished workflow executes every current StepType except CONTINUE_AS_NEW; the test
  asserts the completed type inventory against the enum (REMOVED is intentionally excluded).
  Separate cases execute CONTINUE_AS_NEW same, refuse latest, and check effective versions, marker and
  initiator propagation. Scenarios include nested child processes, forEach items, parallelism,
  HTTP retries, human/agent review, timers/messages, dynamic injection, repeats/loopBacks,
  missing child definitions and unavailable workers. Published definitions remain unchanged.
- Existing engine graph, creation, injection and OpenAPI contract tests are included in the
  focused regression. The non-PostgreSQL engine unit suite passed 1,979 tests during the broader
  regression; the later end-to-end run was interrupted before it produced a final result.
- Shared frontend production build and TypeScript check pass. Public API YAML is generated
  from the actual controllers and checked by EngineOpenApiSpecTest. CI now runs extension tests.

## Graph execution button and blank input templates (0.2.1)

The graph opts into the host execution button; app graphs, forms and read-only execution graphs
retain their existing controls. Tests verify its opt-in/read-only rendering, the intent event,
workspace trust, document binding, ignored webview URIs, serialized preceding edits and refusal
after a failed edit. Input tests cover YAML/JSON, nested references, literals and escaped
expressions, conditions, calls, upstream outputs, forEach arrays/local bindings, scheduling
metadata, engine bindings, timers/messages, prototype paths, recursive aliases and depth bounds.
Host tests verify fresh blank values from unsaved contents, explicit regeneration and prevention
of regeneration after an uncertain submission. No real engine executions are started by these
tests. Browser/native UI automation was unavailable for this update; the button and input flows
were checked through the actual compiled component/host code and production builds.

## Visual and package evidence

The actual execution-panel HTML, graph bundle and webview script were loaded in a browser with
synthetic host messages. Graph selection, child rows, effective versions, history/follow controls,
agent review and literal rendering of HTML-looking log messages were checked. This is a webview
harness, not evidence of a live VS Code connection to deployed services. Initial graph fitting
was corrected after this inspection; the browser was no longer available for the final visual
recheck. The rebuilt graph passes its TypeScript/build checks.

The VSIX ZIP integrity, manifest, runtime dependencies and execution assets were checked. VS Code
successfully installed it in an isolated temporary extension/profile directory. The user's normal
extension installation was not changed. `examples/execution-smoke.ec` uses START and END only,
so the first manual execution needs no task worker or model provider.

## Remaining environment validation

Docker was unavailable during the automated suite runs. The PostgreSQL/distributed multi-pod test suite has not been run. A broad local
attempt exposed existing null-cleanup errors in PostgreSQL tests skipped without Docker; these
unrelated tests were not edited. The focused tests use H2 or memory, so their results do not
establish PostgreSQL concurrency behavior or production readiness.

The automated suites did not exercise real external services, paid models or user credentials. Install the VSIX in
VS Code and connect it to an engine built from this branch with the opt-in enabled. A previously
released engine cannot execute these drafts. Human review, real waits, worker availability and
service credentials retain the normal engine requirements.

## Live local engine validation (2026-10-20)

The current standalone engine was built and deployed as `vscode-test-engine` in the local
`ec-workflow-agent-test` Kubernetes namespace. The existing engine database had a different
migration history, so this instance uses a separate `workflow_vscode_test` database and
separate Kafka topics. Existing engine state was preserved. The test API is enabled on this
instance, with the existing generated local HTTP Basic credentials and a loopback connection
at `http://127.0.0.1:18105`. The connection supervisor retries interrupted port forwards;
it is a local process and does not automatically start after a machine reboot.

A real HTTP execution of `examples/execution-smoke.ec` completed successfully:

- Run `67374c46-b001-473f-9970-1f3aeb4d4fb4`: `COMPLETED`, version 1, `testRun=true`.
- Both `start` and `end`: `COMPLETED`; synthetic input variables were preserved.
- Replaying the same identifier returned HTTP 200 and the existing process.
- Graph, children and logs could be read; an anonymous graph request returned HTTP 401.
- PostgreSQL migration V58 was applied. One test process was persisted and no workflow
  definition was published for this run.

This verifies the live local REST path and basic engine persistence. It does not establish
that the VS Code UI was exercised against this engine, PostgreSQL concurrency behavior,
or external worker/agent integration. This isolated instance requires its own deployed
subprocess definitions and workers connected to its topics for workflows that use them.

## Integrated definition and execution editor (0.2.2)

Execution now reuses the definition editor and its existing graph element. The run controls,
variables, logs, children and history are a collapsible panel in that editor. No independent
execution webview or second graph is constructed. The existing shared graph bundle supplies
editable overlays, palette and property editing; no backend changes are required for this update.

The plugin suite passes 61 tests, including the actual editor and execution bridge scripts with
synthetic host messages. Coverage includes editing while polls update overlays without replacing
the draft; removing states from modified/new steps; preserving layout changes; inspecting child,
historical and continued snapshots in the same canvas; recovering the unsaved draft; preparing a
second run while the first is monitored; retrying the immutable pending request; flushing graph
writes before capture; and waiting for the webview handshake when opened from a command.

TypeScript compilation and VSIX asset/ZIP integrity checks pass. Browser/native UI surfaces were
unavailable, so this update has not been visually verified in a real VS Code window. The live
local engine smoke test recorded above remains backend evidence, not UI interaction evidence.

## Server-side Stop execution (0.2.3)

Stop is part of the shared graph toolbar and integrated panel. The extension host derives its
process ID from the followed view, checks trust, sends the existing cancel command with the same
credentials, and verifies the engine state. Tests cover accepted-but-still-running requests,
permission failures, response loss, bounded idempotent retries, continuation races, history,
arbitrary webview targets, agent review and cancellation verification aborted on view disposal.
No cancellation endpoint or process-lifecycle semantics were added to the backend.

The plugin suite passes 78 tests. The frontend build/typecheck and extension compilation pass.
The existing engine tests also pass: CancelProcessUseCaseTest (16), CancelChildProcessServiceTest
(16), UpdateStepExecutionUseCaseTest (13), and InteractionsTest (7), for 52 backend tests.

Using the actual compiled EngineClient and ExecutionCancellation against the local PostgreSQL /
Kafka engine, a waiting TIMER and a dispatched ACTION were stopped and observed as CANCELLED.
After the timer deadline plus another scheduler interval, their downstream step had not started.
The ACTION's task topic also contained the TaskCancellationRequested for its exact execution ID.
An initial async worker cancellation rolled back after HTTP 202; the bounded retry behavior was
added for this observed case and the subsequent live checks completed with one user stop action.
The native worker remains responsible for honoring cancellation and existing effects are not undone.

Visual interaction in a real VS Code window remains unverified. Runtime checks here use only
synthetic data in the dedicated local test engine, not credentials or services from the remote
engine discussed in chat.

The late-worker integration probe preserved a task request and its matching cancellation from the
synthetic task topic. Its manually produced late reply used rpk's default Snappy compression,
which the Alpine test JVM could not decode. The original synthetic record at upstream partition
0 offset 21 was preserved and replayed uncompressed at offset 22; only the incompatible copy was
skipped. The subsequent local engine restart exposed a full host root filesystem (70 GiB, 100%
used), temporarily preventing a new pod from starting.

Recovery was verified on 2026-10-20 at 14:07 UTC. Space had become available before the authorized
administrative cleanup was attempted, so no writable host-root mount or log truncation was used.
The replacement engine pod is ready; the loopback connection at http://127.0.0.1:18105 was
re-established and its health endpoint reports UP. The consumer processed the preserved reply
at offset 22 and reached offset 23 with zero lag. The engine explicitly logged that it ignored
the late COMPLETED update because the step was already CANCELLED. Both cancelled executions
remain CANCELLED, their downstream steps remain CANCELLED, and the late reply's variable was
not applied. A fresh synthetic START/END test run also completed successfully after recovery.
The host root filesystem still has limited free space (1.9 GiB, 98% used); no unrelated cleanup
was performed. These runtime checks do not exercise a real remote worker's cooperative shutdown.
