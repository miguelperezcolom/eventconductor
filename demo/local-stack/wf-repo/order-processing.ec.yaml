id: order-processing
name: Order processing (demo)
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
  - id: ship
    type: ACTION
    name: Ship order
    topic: shipping-service
    preconditionStepId: charge
  - id: end
    type: END
    name: Done
    preconditionStepId: ship
