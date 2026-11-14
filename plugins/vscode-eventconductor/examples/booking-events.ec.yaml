# A workflow started by a domain event that then waits for another one.
# Events are CloudEvents on logical destinations, mapped to topics by
# workflow.events.destinations.<name>.topic (bookings, payments).
name: Booking onboarding
version: 1
triggers:
  - id: on-booking-created
    destination: bookings
    type: com.acme.booking.created
    filter: "data.channel == 'web'"
    businessKey: "booking-${data.bookingId}"
    variables:
      bookingId: "${data.bookingId}"
      guestEmail: "${data.guest.email}"
      nights: "${data.nights}"
steps:
  - id: start
    type: START
    name: Start
  - id: welcome
    type: ACTION
    name: Send the welcome email
    topic: notifications
    preconditionStepId: start
  - id: wait-payment
    type: WAIT_FOR_MESSAGE
    name: Wait for the payment
    preconditionStepId: welcome
    messageName: payment-captured
    correlationExpression: bookingId
    onEvent:
      destination: payments
      type: com.acme.payment.captured
      correlationKey: "${data.bookingId}"
      filter: "data.status == 'CAPTURED'"
      variables:
        paymentId: "${data.paymentId}"
  - id: confirmed
    type: PUBLISH_EVENT
    name: Booking confirmed
    preconditionStepId: wait-payment
    event:
      destination: bookings
      type: com.acme.booking.confirmed
      key: "${bookingId}"
      payload:
        bookingId: "${bookingId}"
        paymentId: "${paymentId}"
  - id: end
    type: END
    name: End
    preconditionStepId: confirmed
