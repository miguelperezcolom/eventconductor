package io.mateu.workflow.test;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * One dispatch of a task the kit intercepted: what a mock is asked to answer, and what a test can
 * inspect afterwards ({@link WorkflowTestRuntime#invocationsOf(String)}).
 *
 * @param processId            the process the task belongs to
 * @param workflowDefinitionId its definition
 * @param stepId               the step that dispatched it
 * @param taskId               the task reference the engine sent ({@code charge-card@1}, {@code http-call@1},
 *                             {@code complete-form} …), blank for an ACTION with no {@code task:}
 * @param topic                the step's {@code topic}, when it declares one
 * @param attempt              which dispatch of the step execution this is (1 on the first)
 * @param invocation           how many times this step has been dispatched in this process during the
 *                             test, this one included (1 on the first)
 * @param variables            the variables the task was sent, as the engine holds them (text)
 * @param at                   the engine time of the dispatch — what a test of a retry backoff measures
 */
public record TaskInvocation(String processId, String workflowDefinitionId, String stepId, String taskId,
                             String topic, Integer attempt, int invocation, Map<String, String> variables,
                             LocalDateTime at) {

    /** A variable the task was sent, or null. */
    public String variable(String name) {
        return variables.get(name);
    }
}
