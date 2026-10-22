id: vscode-order-routing-demo
name: Order routing and parallel checks
version: 1
description: Synthetic demo. Timers simulate work; no inventory, payment or shipping services are called.
steps:
  - id: start
    type: START
    name: Start

  - id: prepare
    type: TIMER
    name: Prepare order (simulation)
    duration: PT3S
    preconditionStepId: start

  - id: service-level
    type: CHOICE
    name: Choose service level
    preconditionStepId: prepare

  - id: express
    type: TIMER
    name: Express preparation (simulation)
    duration: PT3S
    preconditions:
      - stepId: service-level
        expression: "serviceLevel == 'express'"
        onFalse: DISCARD

  - id: standard
    type: TIMER
    name: Standard preparation (simulation)
    duration: PT6S
    preconditions:
      - stepId: service-level
        expression: "serviceLevel != 'express'"
        onFalse: DISCARD

  - id: selected-route
    type: JOIN
    name: Continue selected route
    joinType: XOR
    preconditionStepIds: [express, standard]

  - id: parallel-checks
    type: FORK
    name: Start parallel checks
    preconditionStepId: selected-route

  - id: inventory
    type: TIMER
    name: Inventory check (simulation)
    duration: PT6S
    preconditionStepId: parallel-checks

  - id: payment
    type: TIMER
    name: Payment check (simulation)
    duration: PT12S
    preconditionStepId: parallel-checks

  - id: all-checks
    type: JOIN
    name: Wait for both checks
    joinType: AND
    preconditionStepIds: [inventory, payment]

  - id: shipping
    type: TIMER
    name: Prepare shipping (simulation)
    duration: PT3S
    preconditionStepId: all-checks

  - id: end
    type: END
    name: Order simulation complete
    preconditionStepId: shipping
