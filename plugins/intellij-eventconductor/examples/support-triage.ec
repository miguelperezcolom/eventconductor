# A support ticket triaged by an agent: the model looks the order up over HTTP, opens a refund through
# a child process once a support lead approves it, and emails the customer — every tool call a real step
# execution, bounded by maxSteps/maxTokens. See https://github.com/miguelperezcolom/eventconductor — guides/agents-with-tools.
name: Triage a support ticket with an agent
version: 1
steps:
  - { id: start, type: START, name: Start }
  - id: triage
    type: AGENT_CALL
    name: Triage the ticket
    preconditionStepId: start
    timeout: PT60S
    retries: 2
    agent:
      connection: claude
      system: You are a support agent for an online shop. Use the tools; never guess an order.
      goal: "Customer ${customerId} wrote: ${message}. Decide whether a refund is due and open it if so."
      tools:
        - name: lookupOrder
          description: Look up an order by its id.
          http: { connection: orders, method: GET, path: "/orders/${orderId}" }
          inputSchema:
            type: object
            required: [orderId]
            properties: { orderId: { type: string } }
        - name: openRefund
          description: Open a refund for an order. A support lead approves it first.
          process: refund-flow
          outputVariables: [refundId]
          approval: { formId: approve-refund, candidateGroups: [support-leads] }
          inputSchema:
            type: object
            required: [orderId, amount]
            properties: { orderId: { type: string }, amount: { type: number, minimum: 0 } }
        - name: notifyCustomer
          description: Email the customer.
          step: notify-customer
      output:
        schema: { type: object, required: [decision], properties: { decision: { type: string } } }
        mapping: { decision: "json.decision" }
      maxSteps: 6
      maxTokens: 50000
  - { id: end, type: END, name: End, preconditionStepId: triage }
  - { id: notify-customer, type: ACTION, name: Notify the customer, topic: emails }
