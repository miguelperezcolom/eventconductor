package io.mateu.workflow.application.usecases.completetask;

import io.mateu.uidl.data.FieldDataType;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.application.out.FormsMetrics;
import io.mateu.workflow.domain.Field;
import io.mateu.workflow.domain.Form;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.FormExecutionStatus;
import io.mateu.workflow.domain.Value;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import io.mateu.workflow.worker.WorkerReply;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.stream.function.StreamBridge;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompleteTaskUseCaseTest {

    @Mock FormExecutionRepository formExecutionRepository;
    @Mock FormRepository formRepository;
    @Mock StreamBridge streamBridge;
    @Mock FormsMetrics formsMetrics;
    @Mock io.mateu.workflow.application.services.TaskAuthorization taskAuthorization;

    @InjectMocks CompleteTaskUseCase useCase;

    private FormExecution formExecution(String id) {
        return FormExecution.builder()
                .id(id).formId("f-1").processId("p-1").stepExecutionId("se-1")
                .status(FormExecutionStatus.PENDING).userId("alice")
                .variables(List.of()).values(List.of()).build();
    }

    /**
     * The form the task is for. Submitted values are checked against it now — a name it does not
     * declare is dropped — so a test that submits a value has to say which form declares it.
     */
    private void formDeclares(String... fieldIds) {
        var fields = java.util.Arrays.stream(fieldIds)
                .map(id -> new Field(id, "Label " + id, FieldDataType.string,
                        FieldStereotype.regular, false, ""))
                .toList();
        when(formRepository.findById("f-1")).thenReturn(Optional.of(new Form("f-1", "My Form", "", fields)));
    }

    /** The broker takes everything it is offered. */
    private void brokerAccepts() {
        when(streamBridge.send(eq("upstream"), any())).thenReturn(true);
    }

    @Test
    void completesTheTaskWithTheSubmittedValues() {
        when(formExecutionRepository.findById("fe-1")).thenReturn(Optional.of(formExecution("fe-1")));
        formDeclares("name");
        brokerAccepts();

        useCase.handle(new CompleteTaskCommand("fe-1", List.of(new Value("name", "John"))));

        ArgumentCaptor<FormExecution> captor = ArgumentCaptor.forClass(FormExecution.class);
        verify(formExecutionRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(FormExecutionStatus.COMPLETED);
        assertThat(captor.getValue().values()).containsExactly(new Value("name", "John"));
    }

    @Test
    void emitsTaskStatusChangedWithTheValuesAsVariables() {
        when(formExecutionRepository.findById("fe-1")).thenReturn(Optional.of(formExecution("fe-1")));
        formDeclares("name");
        brokerAccepts();

        useCase.handle(new CompleteTaskCommand("fe-1", List.of(new Value("name", "John"))));

        var statusChanged = capturedReply();
        assertThat(statusChanged.taskExecutionId()).isEqualTo("se-1");
        assertThat(statusChanged.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(statusChanged.variables()).hasSize(1);
        assertThat(statusChanged.variables().getFirst().name()).isEqualTo("name");
        assertThat(statusChanged.variables().getFirst().value()).isEqualTo("John");
    }

    @Test
    void echoesTheProcessSoTheReplyReachesItsOwner() {
        // Without the key the reply lands wherever the partitioner puts it, and the pod that owns
        // the process has to be told about it second-hand.
        when(formExecutionRepository.findById("fe-1")).thenReturn(Optional.of(formExecution("fe-1")));
        brokerAccepts();

        useCase.handle(new CompleteTaskCommand("fe-1", List.of()));

        assertThat(capturedReply().partitionKey()).isEqualTo("p-1");
    }

    @Test
    void retriesAReplyTheBrokerRefuses() {
        when(formExecutionRepository.findById("fe-1")).thenReturn(Optional.of(formExecution("fe-1")));
        when(streamBridge.send(eq("upstream"), any(TaskLogEmitted.class))).thenReturn(true);
        when(streamBridge.send(eq("upstream"), any(TaskStatusChanged.class)))
                .thenReturn(false).thenReturn(true);

        useCase.handle(new CompleteTaskCommand("fe-1", List.of()));

        verify(streamBridge, times(2)).send(eq("upstream"), any(TaskStatusChanged.class));
        verify(formExecutionRepository).save(any());
    }

    @Test
    void leavesTheTaskOpenWhenTheReplyCannotBePublished() {
        // A form is submitted over HTTP: no offset to leave uncommitted, nothing redelivers it,
        // and a USER_TASK gets no fallback deadline. So a dropped reply would strand the process
        // for good. The task must stay open instead, for the user to submit again.
        when(formExecutionRepository.findById("fe-1")).thenReturn(Optional.of(formExecution("fe-1")));
        when(streamBridge.send(eq("upstream"), any())).thenReturn(false);

        assertThatThrownBy(() -> useCase.handle(new CompleteTaskCommand("fe-1", List.of())))
                .isInstanceOf(WorkerReply.ReplyNotAcceptedException.class);

        verify(formExecutionRepository, never()).save(any());
        verify(formsMetrics, never()).taskCompleted(any(), any());
    }

    @Test
    void aLostLogLineDoesNotStopTheReply() {
        when(formExecutionRepository.findById("fe-1")).thenReturn(Optional.of(formExecution("fe-1")));
        when(streamBridge.send(eq("upstream"), any(TaskLogEmitted.class)))
                .thenThrow(new IllegalStateException("broker refused the log line"));
        when(streamBridge.send(eq("upstream"), any(TaskStatusChanged.class))).thenReturn(true);

        useCase.handle(new CompleteTaskCommand("fe-1", List.of()));

        verify(formExecutionRepository).save(any());
    }

    @Test
    void failsWhenTheTaskDoesNotExist() {
        when(formExecutionRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.handle(new CompleteTaskCommand("missing", List.of())))
                .isInstanceOf(Exception.class);
        verify(formExecutionRepository, never()).save(any());
        verify(streamBridge, never()).send(any(), any());
    }

    private TaskStatusChanged capturedReply() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(streamBridge, times(2)).send(eq("upstream"), captor.capture());
        return captor.getAllValues().stream()
                .filter(TaskStatusChanged.class::isInstance)
                .map(TaskStatusChanged.class::cast)
                .findFirst().orElseThrow();
    }

    /**
     * HARD-SUB-09. A task is completed once. Re-submitting a closed one used to overwrite the
     * values it was completed with and send the engine a second reply — the engine ignores that
     * reply, but the record of what the person actually submitted had already been rewritten.
     */
    @Test
    void aTaskThatIsAlreadyCompletedIsNotCompletedAgain() {
        when(formExecutionRepository.findById("fe-1")).thenReturn(Optional.of(
                formExecution("fe-1").withStatus(FormExecutionStatus.COMPLETED)));

        useCase.handle(new CompleteTaskCommand("fe-1", List.of(new Value("name", "Mallory"))));

        verify(formExecutionRepository, never()).save(any());
        verify(streamBridge, never()).send(anyString(), any());
    }

    /** HARD-SUB-10. And the drop is real end to end, not only in the checker. */
    @Test
    void aValueNamingNoFieldOfTheFormNeverReachesTheEngine() {
        when(formExecutionRepository.findById("fe-1")).thenReturn(Optional.of(formExecution("fe-1")));
        formDeclares("name");
        brokerAccepts();

        useCase.handle(new CompleteTaskCommand("fe-1",
                List.of(new Value("name", "John"), new Value("approved", "true"))));

        assertThat(capturedReply().variables()).extracting(io.mateu.workflow.dtos.Variable::name)
                .containsExactly("name");
    }

    /**
     * AUTHZ-TASK-08. A completion the form does not allow reaches neither the engine nor the row.
     * The check is here, in the use case, rather than in the page, because completion also arrives
     * from the MCP tool — and because the reply below is the one that cannot be taken back: a
     * USER_TASK step that has been told it is done is done.
     */
    @Test
    void aCompletionTheFormForbidsChangesNothingAndTellsTheEngineNothing() {
        when(formExecutionRepository.findById("fe-1")).thenReturn(Optional.of(formExecution("fe-1")));
        when(formRepository.findById("f-1")).thenReturn(Optional.of(new Form("f-1", "My Form", "", List.of(
                new Field("name", "Name", FieldDataType.string, FieldStereotype.regular, false, "")))));
        doThrow(new io.mateu.workflow.security.FlowAuthorizationDeniedException("nope"))
                .when(taskAuthorization).refuseIfCallerMayNot(eq("complete"), any(), eq("fe-1"));

        assertThatThrownBy(() -> useCase.handle(
                new CompleteTaskCommand("fe-1", List.of(new Value("name", "John")))))
                .isInstanceOf(io.mateu.workflow.security.FlowAuthorizationDeniedException.class);

        verify(formExecutionRepository, never()).save(any());
        verify(streamBridge, never()).send(eq("upstream"), argThat(
                message -> message instanceof io.mateu.workflow.dtos.events.integration.TaskStatusChanged));
    }
}
