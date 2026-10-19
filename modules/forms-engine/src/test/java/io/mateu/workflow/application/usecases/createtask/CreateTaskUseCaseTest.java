package io.mateu.workflow.application.usecases.createtask;

import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.out.FormsMetrics;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.FormExecutionStatus;
import io.mateu.workflow.domain.Variable;
import io.mateu.workflow.dtos.events.integration.HumanTaskChanged;
import io.mateu.workflow.dtos.humantasks.HumanTaskAssignment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CreateTaskUseCaseTest {

    @Mock FormExecutionRepository formExecutionRepository;
    @Mock FormsMetrics formsMetrics;
    @Mock io.mateu.workflow.application.services.HumanTaskEvents humanTaskEvents;

    @InjectMocks CreateTaskUseCase useCase;

    private FormExecution saved() {
        ArgumentCaptor<FormExecution> captor = ArgumentCaptor.forClass(FormExecution.class);
        verify(formExecutionRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void createsFormExecutionWithPendingStatus() {
        // CreateTaskCommand(stepExecutionId, processId, workflowDefinitionId, stepId, formId, variables)
        var command = new CreateTaskCommand("se-1", "p-1", "wd-1", "s1", "form-1",
                List.of(new Variable("k", "v")));

        useCase.handle(command);

        FormExecution saved = saved();
        assertThat(saved.formId()).isEqualTo("form-1");
        assertThat(saved.processId()).isEqualTo("p-1");
        assertThat(saved.stepId()).isEqualTo("s1");
        assertThat(saved.stepExecutionId()).isEqualTo("se-1");
        assertThat(saved.status()).isEqualTo(FormExecutionStatus.PENDING);
        assertThat(saved.variables()).hasSize(1);
        assertThat(saved.id()).isNotNull();
        // A task from an engine that sends no assignment names nobody, as every task did before.
        assertThat(saved.isRestricted()).isFalse();
        assertThat(saved.priority()).isEqualTo(50);
        assertThat(saved.createdAt()).isNotNull();
        assertThat(saved.escalations()).isZero();
    }

    @Test
    void anOpenedTaskIsAnnounced() {
        useCase.handle(new CreateTaskCommand("se-1", "p-1", "wd-1", "s1", "form-1", List.of()));

        var saved = saved();
        verify(humanTaskEvents).changed(saved, HumanTaskChanged.CREATED);
        verify(humanTaskEvents, never()).changed(any(), eq(HumanTaskChanged.ASSIGNED));
        verify(formsMetrics).taskCreated("form-1");
    }

    @Test
    void createsFormExecutionWithEmptyValues() {
        useCase.handle(new CreateTaskCommand("se-1", "p-1", "wd-1", "s1", "form-1", null));

        assertThat(saved().values()).isEmpty();
    }

    @Test
    void theAssignmentTheEngineRenderedDecidesWhoTheTaskIsFor() {
        var assignment = new HumanTaskAssignment("alice", List.of("bob"), List.of("/finance", "managers"), 80,
                "2026-10-20T09:00:00Z", "2026-10-19T09:00:00Z", "order-7");
        useCase.handle(new CreateTaskCommand("se-1", "p-1", "wd-1", "s1", "form-1", List.of(
                new Variable("formId", "form-1"),
                new Variable(HumanTaskAssignment.VARIABLE, assignment.toJson()),
                new Variable("__escalations", "0"),
                new Variable("amount", "1200"))));

        var saved = saved();
        assertThat(saved.status()).isEqualTo(FormExecutionStatus.ASSIGNED);
        assertThat(saved.userId()).isEqualTo("alice");
        assertThat(saved.candidateUsers()).containsExactly("bob");
        // Group paths and names compare alike: Keycloak writes /finance, a definition writes finance.
        assertThat(saved.candidateGroups()).containsExactly("finance", "managers");
        assertThat(saved.priority()).isEqualTo(80);
        assertThat(saved.dueDate()).isEqualTo(LocalDateTime.of(2026, 10, 20, 9, 0));
        assertThat(saved.followUpDate()).isEqualTo(LocalDateTime.of(2026, 10, 19, 9, 0));
        assertThat(saved.businessKey()).isEqualTo("order-7");
        assertThat(saved.isRestricted()).isTrue();
        // The engine's own variables are not the process's: the task does not show them.
        assertThat(saved.variables()).extracting(Variable::name).containsExactly("formId", "amount");
        verify(humanTaskEvents).changed(saved, HumanTaskChanged.CREATED);
        verify(humanTaskEvents).changed(saved, HumanTaskChanged.ASSIGNED);
    }

    @Test
    void anUnreadableAssignmentOrDateIsNobodyRatherThanAFailure() {
        useCase.handle(new CreateTaskCommand("se-1", "p-1", "wd-1", "s1", "form-1", List.of(
                new Variable(HumanTaskAssignment.VARIABLE, "{not json"))));

        var saved = saved();
        assertThat(saved.isRestricted()).isFalse();
        assertThat(saved.status()).isEqualTo(FormExecutionStatus.PENDING);
        assertThat(CreateTaskUseCase.utc("next tuesday")).isNull();
        assertThat(CreateTaskUseCase.utc(" ")).isNull();
        assertThat(CreateTaskUseCase.groups(null)).isEmpty();
        assertThat(CreateTaskUseCase.groups(List.of("/", "a", "/a"))).containsExactly("a");
    }
}
