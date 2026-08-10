package io.mateu.workflow.dtos;

import io.mateu.workflow.dtos.events.domain.ProcessCancellationRequested;
import io.mateu.workflow.dtos.events.domain.ProcessCreated;
import io.mateu.workflow.dtos.events.domain.StepExecutionStatusChanged;
import io.mateu.workflow.dtos.events.integration.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DtosTest {

    @Test
    void variableHoldsNameAndValue() {
        var v = new Variable("key", "value");
        assertThat(v.name()).isEqualTo("key");
        assertThat(v.value()).isEqualTo("value");
    }

    @Test
    void messageTypeEnumValues() {
        assertThat(MessageType.values()).contains(MessageType.Info, MessageType.Error);
    }

    @Test
    void operationEnumValues() {
        assertThat(Operation.values()).hasSizeGreaterThan(0);
    }

    @Test
    void outboxEventHoldsFields() {
        var event = new OutboxEvent("type1", "id1", Operation.Create, "{}");
        assertThat(event.type()).isEqualTo("type1");
        assertThat(event.id()).isEqualTo("id1");
        assertThat(event.payload()).isEqualTo("{}");
    }

    @Test
    void processCreatedHoldsProcessId() {
        var vars = List.of(new Variable("k", "v"));
        var event = new ProcessCreated("p-1", vars);
        assertThat(event.processId()).isEqualTo("p-1");
        assertThat(event.variables()).hasSize(1);
    }

    @Test
    void processCreationRequestedHoldsFields() {
        var event = new ProcessCreationRequested("wd-1", "BK", List.of());
        assertThat(event.workflowDefinitionId()).isEqualTo("wd-1");
        assertThat(event.businessKey()).isEqualTo("BK");
    }

    @Test
    void processCancellationRequestedHoldsBusinessKey() {
        var event = new ProcessCancellationRequested("BK-1");
        assertThat(event.businessKey()).isEqualTo("BK-1");
    }

    @Test
    void taskExecutionRequestedHoldsFields() {
        var event = new TaskExecutionRequested("se-1", "p-1", "wd-1", "s1", "task", List.of());
        assertThat(event.taskExecutionId()).isEqualTo("se-1");
        assertThat(event.taskId()).isEqualTo("task");
    }

    @Test
    void taskStatusChangedHoldsFields() {
        var event = new TaskStatusChanged("se-1", TaskStatus.COMPLETED, List.of());
        assertThat(event.taskExecutionId()).isEqualTo("se-1");
        assertThat(event.status()).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    void taskStatusEnumValues() {
        assertThat(TaskStatus.values()).contains(TaskStatus.COMPLETED, TaskStatus.ERROR, TaskStatus.TIMEOUT);
    }

    @Test
    void taskLogEmittedHoldsFields() {
        var event = new TaskLogEmitted("se-1", MessageType.Info, "message");
        assertThat(event.taskExecutionId()).isEqualTo("se-1");
        assertThat(event.message()).isEqualTo("message");
    }

    @Test
    void taskCancellationRequestedHoldsId() {
        var event = new TaskCancellationRequested("se-1");
        assertThat(event.taskId()).isEqualTo("se-1");
    }

    @Test
    void timeoutCheckRequestedHoldsProcessId() {
        var event = new TimeoutCheckRequested("p-1");
        assertThat(event.processId()).isEqualTo("p-1");
    }

    @Test
    void stepExecutionStatusChangedHoldsFields() {
        var event = new StepExecutionStatusChanged("se-1", TaskStatus.RUNNING, List.of());
        assertThat(event.stepExecutionId()).isEqualTo("se-1");
        assertThat(event.status()).isEqualTo(TaskStatus.RUNNING);
    }

    @Test
    void taskResourceCreatedHoldsFields() {
        var event = new TaskResourceCreated("se-1", "res-1", "pdf", "report.pdf", "http://example.com");
        assertThat(event.taskExecutionId()).isEqualTo("se-1");
        assertThat(event.resourceType()).isEqualTo("pdf");
    }

    @Test
    void stepsInjectedHoldsFieldsAndKeysOnProcess() {
        var event = new StepsInjected("se-1", "process-1", "[]");
        assertThat(event.taskExecutionId()).isEqualTo("se-1");
        assertThat(event.processId()).isEqualTo("process-1");
        assertThat(event.stepsJson()).isEqualTo("[]");
        assertThat(event.partitionKey()).isEqualTo("process-1");
    }
}
