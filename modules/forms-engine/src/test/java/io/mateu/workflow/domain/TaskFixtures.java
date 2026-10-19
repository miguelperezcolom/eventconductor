package io.mateu.workflow.domain;

import java.util.List;

/** Tasks as tests build them: the shape a task had before assignment, the rest left empty. */
public final class TaskFixtures {

    private TaskFixtures() {
    }

    public static FormExecution task(String id, String formId, String processId, String stepId, String stepExecutionId,
                                     FormExecutionStatus status, String userId, String userGroup,
                                     List<Variable> variables, List<Value> values) {
        return new FormExecution(id, formId, processId, stepId, stepExecutionId, status, userId, userGroup,
                variables, values, null, null, null, null, null, null, null, null, null, null);
    }
}
