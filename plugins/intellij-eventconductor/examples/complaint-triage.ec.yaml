# A guest complaint triaged by a language model, with no worker written for it.
# The model and its key are the deployment's (workflow.llm.connections.claude); this file only names
# the connection. See https://github.com/miguelperezcolom/eventconductor — guides/llm-calls.
name: Complaint triage
version: 1
steps:
  - id: start
    type: START
    name: Start
  - id: triage
    type: LLM_CALL
    name: Triage the complaint
    preconditionStepId: start
    timeout: PT60S
    retries: 2
    llm:
      connection: claude
      system: You triage hotel guest complaints. Be strict about urgency.
      prompt: |
        Guest ${guestName} (booking ${bookingId}) wrote:
        ${complaint}
      maxTokens: 1024
      responseSchema:
        type: object
        required: [category, urgency, summary]
        properties:
          category: { type: string, enum: [room, billing, staff, other] }
          urgency: { type: integer, minimum: 1, maximum: 5 }
          summary: { type: string }
      output:
        category: json.category
        urgency: json.urgency
        summary: json.summary
      usageVariable: triageUsage
  - id: route
    type: CHOICE
    name: Urgent?
    preconditionStepId: triage
  - id: escalate
    type: ACTION
    name: Escalate to the duty manager
    topic: front-desk
    preconditions:
      - stepId: route
        expression: urgency >= 4
  - id: queue
    type: ACTION
    name: Queue for the guest-relations team
    topic: guest-relations
    preconditions:
      - stepId: route
  - id: merge
    type: JOIN
    name: Either way
    joinType: XOR
    preconditionStepIds: [escalate, queue]
  - id: apologise
    type: LLM_CALL
    name: Draft the apology
    preconditionStepId: merge
    llm:
      connection: claude
      prompt: |
        Write a short, warm apology to ${guestName} about: ${summary}.
        Two sentences, no promises of compensation.
      outputVariable: apologyDraft
  - id: end
    type: END
    name: Done
    preconditionStepId: apologise
