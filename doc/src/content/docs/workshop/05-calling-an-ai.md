---
title: "5 · Calling an AI"
description: A language model in the process — classify the guest's special request with structured output, draft the confirmation email — and an AGENT step with human review.
---

Guests write special requests in free text: *"I use a wheelchair"*, *"we travel with a baby"*, *"we arrive after midnight"*. A language model can read them, classify them for the front desk, and draft the confirmation email — as ordinary steps of the process, with the same retries, timeouts and visibility as any other.

:::note[No key needed — and none sent anywhere]
The workshop runs against a **fake model** the app serves itself (`FakeLanguageModel.java`, at `/fake/llm`, speaking the OpenAI chat-completions API). It always classifies the same way and always drafts the same email — it is there so the steps run, not to be clever. To use a real model, change the connection, not the definitions:

```properties
# Anthropic
workflow.llm.connections.assistant.provider=anthropic
workflow.llm.connections.assistant.model=claude-sonnet-5-5
workflow.llm.connections.assistant.api-key=${ANTHROPIC_API_KEY}
# or OpenAI / any OpenAI-compatible server: provider=openai, base-url (the API root with /v1,
# e.g. http://localhost:11434/v1 for Ollama), model, api-key
```

Take the key from the environment or a secret store — never from a file in the repository, and never in a definition (the build refuses one). See [LLM Calls](/guides/llm-calls/#connections).
:::

## The definition

`05-special-request.yaml`:

```yaml
id: special-request
name: Handle the guest's special request
steps:
  - id: start
    type: START
    name: Booking received

  - id: classify-request
    type: LLM_CALL
    name: Understand the special request
    preconditionStepId: start
    timeout: PT60S
    retries: 2
    llm:
      connection: assistant                # workflow.llm.connections.assistant
      system: "You read hotel guests' special requests and classify them for the front desk."
      prompt: "Guest ${guest} wrote: ${specialRequest}"
      responseSchema:                      # the answer must be JSON valid against this
        type: object
        required: [category, needsStaff, summary]
        properties:
          category: { type: string, enum: [accessibility, family, arrival, other] }
          needsStaff: { type: boolean }
          summary: { type: string }
      output:
        requestCategory: "json.category"
        needsStaff: "json.needsStaff"
      usageVariable: classifyUsage

  - id: draft-confirmation
    type: LLM_CALL
    name: Draft the confirmation email
    preconditionStepId: classify-request
    llm:
      connection: assistant
      prompt: |
        Write a short, warm confirmation email to ${guest}.
        Their special request (${requestCategory}) is being prepared.
      outputVariable: confirmationEmail

  - id: notify-guest
    type: ACTION
    name: Send it to the guest
    preconditionStepId: draft-confirmation

  - id: done
    type: END
    name: Done
    preconditionStepId: notify-guest
```

- **`responseSchema`** makes the answer structured: it must be JSON valid against the schema, or the step fails with `LLM_INVALID_OUTPUT` and is retried — models sometimes need a second try. The `output` expressions read fields of the parsed answer (`json.category`).
- **`usageVariable`** records the tokens the call spent, per run.
- Prompts and answers are **never logged**: they carry the process's data.

## Run it

```bash
scripts/start.sh special-request BOOKING-6 '{"guest": "Erin",
  "specialRequest": "I use a wheelchair, please make sure the room is accessible",
  "rooms": [{"number": "101", "type": "double"}]}'
```

![The variables the model produced: requestCategory, needsStaff, confirmationEmail, and the token usage](/workshop/05-llm-variables.jpg)

## Agents: the `AGENT` step

An `LLM_CALL` is one question and one answer. An **agent** plans, calls tools, and may take minutes — and an action whose effect is uncertain must be reviewed by a person before the process goes on. The `AGENT` step invokes an agent published in the IA catalogue, which owns its prompt, model and tools; the workflow only chooses the task and the input:

```yaml
- id: prepare-request
  type: AGENT
  name: Prepare the special request
  preconditionStepId: start
  timeout: PT5M
  retries: 0                          # an agent failure is final: a paid model call is not repeated
  agent:
    ref: front-desk-assistant         # the published agent
    taskPrompt: "Prepare what the front desk needs for this request: ${specialRequest}"
    input:
      guest: "${guest}"
      request: "${specialRequest}"
    outputVariable: preparation
```

When the agent reports `REVIEW_REQUIRED`, the process is **paused** until the agent runtime reconciles it — completing, retrying or migrating it by hand is refused meanwhile. See [Agents in workflows](/guides/workflow-agents/).

:::caution[Not run live in this workshop]
`AGENT` needs the IA catalogue and an agent runtime, which this laptop setup does not include: a definition with an `AGENT` step is refused at import when the catalogue cannot be read. The workshop exercises it **in a test** instead, where the test kit plays the catalogue and the runtime — including the review — in [Part 9 · Simulated integrations](/workshop/testing/integrations/).
:::

## Check

- [ ] BOOKING-6 completed with `requestCategory = accessibility` and a `confirmationEmail`.
- [ ] `classifyUsage` shows the model and the tokens spent.
