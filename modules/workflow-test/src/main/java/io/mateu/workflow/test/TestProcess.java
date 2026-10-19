package io.mateu.workflow.test;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessReply;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A process a test started (or found). A handle, not a snapshot: every read goes to the engine, so
 * after {@code advanceTime} or {@code sendMessage} it shows the process as it is now. Assert on it
 * with {@link WorkflowAssertions#assertThat(TestProcess)}.
 */
public final class TestProcess {

    private final WorkflowTestRuntime runtime;
    private final String id;
    private final String businessKey;
    private final String definitionId;

    TestProcess(WorkflowTestRuntime runtime, String id, String businessKey, String definitionId) {
        this.runtime = runtime;
        this.id = id;
        this.businessKey = businessKey;
        this.definitionId = definitionId;
    }

    public String id() {
        return id;
    }

    public String businessKey() {
        return businessKey;
    }

    public String definitionId() {
        return definitionId;
    }

    /** The engine's process, read now. */
    public Process state() {
        return runtime.processState(id);
    }

    public ProcessStatus status() {
        return state().getStatus();
    }

    /** The process variables, as the engine holds them (text; structured values as JSON). */
    public Map<String, String> variables() {
        var variables = new LinkedHashMap<String, String>();
        var list = state().getVariables();
        if (list != null) {
            list.forEach(variable -> variables.put(variable.name(), variable.value()));
        }
        return variables;
    }

    /** One variable, or null. */
    public String variable(String name) {
        return variables().get(name);
    }

    /** Every step execution of the process, as the engine lists them. */
    public List<StepExecution> stepExecutions() {
        return runtime.stepExecutions(id);
    }

    /** The executions of one step — more than one for a step a loop or a rerun went through. */
    public List<StepExecution> stepExecutions(String stepId) {
        return stepExecutions().stream().filter(execution -> stepId.equals(execution.getStepId())).toList();
    }

    /** What the process answered (a REPLY step, or the engine on its behalf), or null. */
    public ProcessReply reply() {
        return state().getReply();
    }

    /** The updates the process received, oldest first — what was asked, by whom, how it ended. */
    public List<io.mateu.workflow.application.updates.ProcessUpdate> updates() {
        return runtime.updatesOf(id);
    }

    WorkflowTestRuntime runtime() {
        return runtime;
    }

    @Override
    public String toString() {
        return "process " + id + " (" + definitionId + ", businessKey " + businessKey + ")";
    }
}
