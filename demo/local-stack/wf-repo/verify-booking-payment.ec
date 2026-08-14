id: verify-booking-payment
name: Verify booking payment
steps:
  - id: start
    type: START
    name: Start
  # A human verifies the payment, with a 30s step timeout. On timeout the flow routes natively to
  # cancelar-reserva (no FORK/TIMER needed) via onTimeoutStepId.
  - id: verify-payment
    type: USER_TASK
    name: Verify payment received
    formId: verify-payment
    timeout: 30000
    onTimeoutStepId: cancelar-reserva
    preconditionStepId: start
  # Payment confirmed by the human → confirm the booking.
  - id: confirmar-reserva
    type: ACTION
    name: Confirm booking
    topic: booking
    preconditions:
      - stepId: verify-payment
        expression: "paymentReceived == 'true'"
  # Human rejection OR the 30s timeout (via onTimeoutStepId above) both reach this single step.
  - id: cancelar-reserva
    type: ACTION
    name: Cancel booking
    topic: booking
    preconditions:
      - stepId: verify-payment
        expression: "paymentReceived == 'false'"
  # Ends as soon as one outcome completes; END then cancels the losing branch's pending step.
  - id: done
    type: JOIN
    name: Done
    joinType: XOR
    preconditionStepIds: [confirmar-reserva, cancelar-reserva]
  - id: end
    type: END
    name: End
    preconditionStepId: done
