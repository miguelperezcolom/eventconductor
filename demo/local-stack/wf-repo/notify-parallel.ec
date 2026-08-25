id: notify-parallel
name: Parallel notifications (FORK / JOIN·AND)
version: 1
steps:
  - id: start
    type: START
    name: Start
  - id: fanout
    type: FORK
    name: Notify all channels
    preconditionStepId: start
  - id: email
    type: ACTION
    name: Send email
    topic: email-sender
    preconditionStepId: fanout
  - id: sms
    type: ACTION
    name: Send SMS
    topic: sms-sender
    preconditionStepId: fanout
  - id: joined
    type: JOIN
    name: All notifications sent
    joinType: AND
    preconditionStepIds:
      - email
      - sms
  - id: end
    type: END
    name: Done
    preconditionStepId: joined
