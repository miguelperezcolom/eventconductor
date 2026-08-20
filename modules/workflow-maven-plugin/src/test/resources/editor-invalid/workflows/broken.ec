# Two steps sharing an id, which the specification forbids — and which the validator has to see
# through an extension it used to skip.
id: broken
name: Broken
version: 1
steps:
  - id: start
    type: START
    name: Start
  - id: twice
    type: ACTION
    name: First
    topic: work
    preconditionStepId: start
  - id: twice
    type: ACTION
    name: Second
    topic: work
    preconditionStepId: start
  - id: end
    type: END
    name: End
    preconditionStepId: twice
