name: Synthetic agent assessment
steps:
  - id: start
    type: START
    name: Start
  - id: assess
    type: AGENT
    name: Assess synthetic case
    preconditionStepId: start
    agent:
      ref: workflow-case-assessor
      taskPrompt: Assess the supplied synthetic case.
      input:
        caseId: "${caseId}"
      outputVariable: assessment
    timeout: 300000
    retries: 0
  - id: end
    type: END
    name: End
    preconditionStepId: assess
