# Reserve every room of a booking, poll the payment until it settles, and send the guest back to
# fix their details while the check says so — loops and multi-instance, declared in the file.
# See https://github.com/miguelperezcolom/eventconductor — guides/loops-and-multi-instance.
name: Room reservations
version: 1
steps:
  - id: start
    type: START
    name: Start
  - id: reserve
    type: ACTION
    name: Reserve a room
    topic: booking
    preconditionStepId: start
    retries: 2
    compensable: true
    compensationStepId: release
    forEach:
      items: rooms
      as: room
      mode: parallel
      maxConcurrency: 3
      output: reservations
      outputElement: reservationId
  - id: poll-payment
    type: ACTION
    name: Poll the payment
    topic: payments
    preconditionStepId: reserve
    repeat:
      until: "paymentStatus == 'SETTLED'"
      maxIterations: 20
      delay: PT30S
  - id: check-details
    type: ACTION
    name: Check guest details
    topic: booking
    preconditionStepId: poll-payment
  - id: details-ok
    type: CHOICE
    name: Details complete?
    preconditionStepId: check-details
  - id: ask-guest
    type: USER_TASK
    name: Ask the guest to fix them
    formId: guest-details
    preconditions:
      - stepId: details-ok
        expression: "detailsComplete != 'true'"
    loopBack:
      to: check-details
      maxIterations: 3
  - id: confirm
    type: ACTION
    name: Confirm the booking
    topic: booking
    preconditions:
      - stepId: details-ok
  - id: release
    type: ACTION
    name: Release a room
    topic: booking
  - id: end
    type: END
    name: End
    preconditionStepId: confirm
