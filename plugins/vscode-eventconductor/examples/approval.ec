name: Expense approval
version: 1
status: ACTIVE
steps:
  - id: start
    type: START
    name: Start
  - id: submit
    type: ACTION
    name: Submit expense
    topic: expenses
    preconditionStepId: start
  - id: fork
    type: FORK
    name: Route
    preconditionStepId: submit
  - id: auto
    type: RULE
    name: Auto-approve small
    ruleId: small-amount
    preconditionStepId: fork
    preconditionExpression: "amount <= 100"
  - id: manager
    type: USER_TASK
    name: Manager approval
    formId: approve-form
    preconditionStepId: fork
    preconditionExpression: "amount > 100"
  - id: approved
    type: JOIN
    name: Approved
    joinType: XOR
    preconditionStepIds: [auto, manager]
  - id: pay
    type: ACTION
    name: Pay
    topic: payments
    preconditionStepId: approved
  - id: end
    type: END
    name: Done
    preconditionStepId: pay
