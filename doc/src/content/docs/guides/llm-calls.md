---
title: LLM Calls
description: The LLM_CALL step — ask a language model (Claude, or any OpenAI-compatible model) from the process's data and map its answer, structured or not, into variables, without writing a worker.
---

`LLM_CALL` asks a language model a question built from the process's data and puts the answer into process variables — classification, extraction, summarization, drafting — the step you would otherwise write a worker for.

```yaml
- id: triage
  type: LLM_CALL
  name: Triage the complaint
  preconditionStepId: log
  timeout: PT60S
  retries: 2
  compensable: true
  compensationStepId: reopen
  llm:
    connection: claude                  # workflow.llm.connections.claude: provider, model, key
    # model: claude-opus-5-5            # overrides the connection's model
    system: "You triage hotel guest complaints."
    prompt: |
      Guest ${guestName} (booking ${bookingId}) wrote:
      ${complaint}
    maxTokens: 1024                     # default: the connection's (4096)
    # temperature: 0.2                  # only when set — see below
    responseSchema:                     # optional: the answer must be JSON valid against it
      type: object
      required: [category, urgency, summary]
      properties:
        category: { type: string, enum: [room, billing, staff, other] }
        urgency:  { type: integer, minimum: 1, maximum: 5 }
        summary:  { type: string }
    output:                             # variable ← JEXL over {text, json, usage, model, finishReason}
      category: "json.category"
      urgency: "json.urgency"
      urgent: "json.urgency >= 4"
    outputVariable: triage              # the whole answer (here the JSON document) into one variable
    usageVariable: triageUsage          # {model, inputTokens, outputTokens, totalTokens}
```

Without a `responseSchema` the answer is text:

```yaml
  llm:
    connection: claude
    prompt: "Write a two-line apology to ${guestName} for: ${complaint}"
    outputVariable: apology
```

## The `llm` block

| Field | | |
|---|---|---|
| `connection` | **required** | A named connection, `workflow.llm.connections.<name>` |
| `prompt` | **required** | The user message — a [text template](/guides/payload-templates/) over the process variables |
| `system` | | The system prompt — a text template |
| `model` | | Overrides the connection's model |
| `maxTokens` | | The most output tokens; default the connection's |
| `temperature` | | 0–2; sent only when set (on the connection or the step). Current Claude models accept only the default — leave it unset for them |
| `responseSchema` | | A JSON Schema; the answer must be one JSON document valid against it |
| `output` | | Variable ← JEXL over `{text, json, usage, model, finishReason}` (`json` is the parsed answer) |
| `outputVariable` | | The variable that receives the answer's text (with `responseSchema`, the JSON document) |
| `usageVariable` | | The variable that receives `{model, inputTokens, outputTokens, totalTokens}` |

One of `output` and `outputVariable` is required — an answer nobody keeps is a definition error. Like every template, the prompts see each process variable by name, `process`, `step`, `now` and `businessKey`.

**No key, no secret, ever, in a definition.** A definition names a connection; the API key is the connection's configuration. An `apiKey`/`token` field in an `llm` block, or a `${secret:…}` reference in a prompt, fails the build and the import — a secret has no business in a prompt.

## How it runs

The engine renders `system` and `prompt` from the process as it stands and dispatches them to the built-in task **`llm-call@1`**. So everything a worker step has applies: `timeout` (the default step timeout too), `retries` with backoff, `deadline`, compensation, cancellation, the stalled-step watch. The call itself is made by **`worker-llm`**, on [Spring AI](https://docs.spring.io/spring-ai/reference/)'s chat models:

- **embedded mode**: add `io.mateu.workflow:builtin-workers` (every built-in step in one dependency) or `io.mateu.workflow:worker-llm` and `io.mateu.workflow:worker-embedded` (which runs the built-in task in-process), plus a provider — `org.springframework.ai:spring-ai-anthropic` and/or `spring-ai-openai` — to the application; the call runs in the engine's pod. As for HTTP calls, don't declare your own `EmbeddedTaskExecutor` bean alongside it.
- **kafka mode**: the task goes to topic **`llm-calls`** (a step's `topic` overrides), served by the **orchestrator itself** (`orchestrator-standalone-app` runs the built-in workers, configured with the same `workflow.llm.*` properties) — no worker to deploy. Optionally, **`llm-worker-standalone-app`** (image `llm-worker-standalone-app`, both providers included) or any worker with `worker-llm` on its classpath serves it instead — so the pods that hold the API keys and talk to the providers scale and are network-policed apart from the engine; then take `llm-calls` out of the orchestrator's `BUILTIN_WORKER_TOPICS` and set `workflow.llm.enabled=false` there (the Helm chart does both when `llmWorker.enabled`). Never let both serve the topic: two consumer groups run every call twice.

## Structured output

With a `responseSchema`, the schema goes into the system prompt with the instruction to answer with one JSON document and nothing else. The answer is parsed — a Markdown code fence around it is tolerated — and validated against the schema (JSON Schema 2020-12). Then `json` in `output` is the parsed document, and `outputVariable` receives it as JSON text.

An answer that is not JSON, or does not validate, fails the step with **`LLM_INVALID_OUTPUT`** and the validation errors in the Errors tab ("…/urgency: must have a maximum value of 5"). It is **retryable**: another sample is often valid, so the step's `retries` apply. If the answer was cut short by `maxTokens`, the reason says so.

A connection with `structured-output: native` also hands the schema to the provider's own structured-output support, which constrains the model as it generates. The schema must then be one the provider accepts (Anthropic and OpenAI want `additionalProperties: false` on every object, for instance); the answer is validated all the same.

## Failures and retries

| Failure | Code | Retried? |
|---|---|---|
| The provider refuses the request: 400, 401, 403, 404, 413, 422… | `LLM_<status>` | **No** — the same request, or the same key, fails the same way; the compensation runs at once |
| Rate limited, overloaded, server error: 408, 409, 429, 5xx (529) | `LLM_<status>` | Yes, per the step's `retries` |
| Timeout, connection error | `LLM_IO` | Yes |
| The answer does not match the `responseSchema` | `LLM_INVALID_OUTPUT` | Yes |
| Unknown connection, no `api-key`, provider not on the classpath, invalid `responseSchema` | `LLM_CONFIG` | No |

The provider SDKs' own retries are switched off: the step's `retries` are the one retry policy, so every attempt shows in the process and in the metrics.

Prompts and answers are **never logged** — they carry the process's data. The worker's log line has the connection, model, token counts, duration and outcome.

## Connections

```yaml
workflow:
  llm:
    connections:
      claude:
        provider: anthropic
        model: claude-sonnet-5-5
        api-key: ${ANTHROPIC_API_KEY}           # from the environment, a K8s secret, Vault…
        max-tokens: 4096
        timeout: PT60S
      opus:
        provider: anthropic
        model: claude-opus-5-5
        api-key: ${ANTHROPIC_API_KEY}
        timeout: PT5M
      local:
        provider: openai                        # OpenAI, or any OpenAI-compatible server
        base-url: http://vllm.internal:8000
        model: llama-4-scout
        api-key: ${LOCAL_LLM_API_KEY}
        temperature: 0.2
        structured-output: native
```

| Key | Default | |
|---|---|---|
| `provider` | `anthropic` | `anthropic` or `openai` |
| `model` | — | The model steps use unless they name another |
| `base-url` | the provider's | A gateway, a proxy, a self-hosted OpenAI-compatible server |
| `api-key` | — | Required. Configuration only |
| `temperature` | unset | Default temperature |
| `max-tokens` | `4096` | Default most output tokens |
| `timeout` | `PT60S` | One call's limit; a longer one fails as `LLM_IO` |
| `structured-output` | `prompt` | `prompt` or `native` (above) |

`workflow.llm.topic` (default `llm-calls`) is the topic the worker serves in kafka mode; `workflow.llm.enabled=false` leaves the task out. An application that builds its models some other way provides its own `ChatModelFactory` bean.

## Metrics

Spring AI records its own observations for every call — `gen_ai_client_operation_seconds_*` and `gen_ai_client_token_usage_total`, by provider and model. `worker-llm` adds what only the step knows:

| Metric | Prometheus | Tags |
|---|---|---|
| Timer `eventconductor.llm.call` | `eventconductor_llm_call_seconds_count/sum/max` | `workflow`, `step`, `connection`, `model`, `outcome` (`success` \| `error` \| `invalid_output`) |
| Counter `eventconductor.llm.tokens` | `eventconductor_llm_tokens_total` | `workflow`, `step`, `connection`, `model`, `type` (`input` \| `output`) |

So "tokens per workflow per day" is `sum by (workflow, type) (increase(eventconductor_llm_tokens_total[1d]))`, and the share of answers that missed their schema is `sum(rate(eventconductor_llm_call_seconds_count{outcome="invalid_output"}[5m])) / sum(rate(eventconductor_llm_call_seconds_count[5m]))`.

The token usage of a single run is in `usageVariable` when the step asks for it, and shows in the process view like any variable.
