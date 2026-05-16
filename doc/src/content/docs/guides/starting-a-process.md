---
title: Starting a Process
description: How to start a workflow process instance — via Kafka or programmatically.
---

A process instance is created by sending a `ProcessCreationRequested` event. Depending on your deployment mode, you can do this via Kafka or programmatically.

## Via Kafka (mode: kafka)

Send a `ProcessCreationRequested` event to the `upstream` topic:

```json
{
  "@type": "ProcessCreationRequested",
  "workflowDefinitionId": "my-workflow",
  "businessKey": "order-123",
  "variables": [
    { "name": "orderId", "value": "123" },
    { "name": "amount",  "value": "99.90" }
  ]
}
```

The `businessKey` is an optional human-readable identifier (e.g. an order number) that lets you look up the process later.

## Programmatically (any mode)

Inject `ProcessUpstreamEventUseCase` and call it directly:

```java
@Autowired
ProcessUpstreamEventUseCase processUpstreamEventUseCase;

public void startOrder(String orderId, String amount) {
    processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(
        new ProcessCreationRequested(
            "my-workflow",
            "order-" + orderId,
            List.of(
                new Variable("orderId", orderId),
                new Variable("amount", amount)
            )
        )
    ));
}
```

## Process variables

Variables are key-value pairs attached to the process. They are:

- Passed at creation time
- Merged with output variables reported by each worker
- Available to JEXL precondition expressions in step definitions
- Included in the process state returned by the MCP `getProcessDetails` tool

Variables are typed as strings. Numeric comparisons in JEXL expressions work on the string representation.

## Looking up a process

Use `ProcessRepository` to query by ID or business key:

```java
@Autowired
ProcessRepository processRepository;

// By ID
Process process = processRepository.findById(processId);

// By business key
Process process = processRepository.findByBusinessKey("order-123");
```

## Cancelling a process

Send a `ProcessCancellationRequested` event via `ProcessUpstreamEventUseCase`:

```java
processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(
    new ProcessCancellationRequested(processId)
));
```

Running step executions will be cancelled, and the process status will transition to `CANCELLED`.
