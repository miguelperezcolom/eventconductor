# What the graph editor and both IDE plugins write: YAML, under .ec.
id: onboarding
name: Onboarding
version: 1
steps:
  - id: start
    type: START
    name: Start
  - id: greet
    type: ACTION
    name: Greet
    topic: work
    preconditionStepId: start
  - id: end
    type: END
    name: End
    preconditionStepId: greet
