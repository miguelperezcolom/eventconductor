name: Order processing
version: 1
steps:
  - id: start
    type: START
    name: Start
  - id: validate
    type: ACTION
    name: Validate order
    topic: order-validator
    preconditionStepId: start
  - id: charge
    type: ACTION
    name: Charge card
    topic: payment-service
    preconditionStepId: validate
    timeout: 30000
    retries: 2
    compensable: true
    compensationStepId: refund
  - id: refund
    type: ACTION
    name: Refund card
    topic: payment-service
  - id: end
    type: END
    name: Done
    preconditionStepId: charge
