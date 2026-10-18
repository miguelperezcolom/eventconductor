package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.IncidentRepository;
import io.mateu.workflow.application.out.IncidentRepository.FailedStep;
import io.mateu.workflow.application.usecases.process.operate.OperatorAudit;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.events.integration.RetryStepExecutionRequested;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.FlowAuthorizationDeniedException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class IncidentServiceTest {

    final List<FailedStep> rows = new ArrayList<>();
    final IncidentRepository repository = (definitionId, limit) -> rows.stream()
            .filter(row -> definitionId == null || definitionId.equals(row.workflowDefinitionId()))
            .limit(limit).toList();
    final List<DomainEvent> dispatched = new CopyOnWriteArrayList<>();
    final ProcessOperationsService operations = mock(ProcessOperationsService.class);
    final OperatorAudit audit = mock(OperatorAudit.class);
    static final AuthorizationContext ALICE = new AuthorizationContext("alice", List.of(), List.of());

    IncidentService service(int scanLimit, int maxSteps, int syncLimit) {
        return new IncidentService(repository, new CommandDispatcher(dispatched::add, dispatched::add, false),
                operations, audit, scanLimit, maxSteps, syncLimit);
    }

    @Test
    void familiesTakeOutWhatVariesBetweenOccurrences() {
        assertThat(IncidentService.family("Payment 4711 declined for 'ACME'")).isEqualTo("Payment # declined for '…'");
        assertThat(IncidentService.family("[non-retryable] HTTP 503 from \"https://x/1\"\nstack…"))
                .isEqualTo("HTTP # from \"…\"");
        assertThat(IncidentService.family("order 3f2a9c1b-0000-4000-8000-1234567890ab missing"))
                .isEqualTo("order <id> missing");
        assertThat(IncidentService.family("trace deadbeefcafe0123 lost")).isEqualTo("trace <id> lost");
        assertThat(IncidentService.family(null)).isEqualTo(IncidentService.NO_MESSAGE);
        assertThat(IncidentService.family("   ")).isEqualTo(IncidentService.NO_MESSAGE);
        assertThat(IncidentService.family("x".repeat(300))).hasSize(161);
    }

    @Test
    void groupsByDefinitionStepAndFamilyMostFailuresFirst() {
        rows.add(row("p1", "orders", "charge", "Card 1 declined", 1));
        rows.add(row("p2", "orders", "charge", "Card 2 declined", 2));
        rows.add(row("p2", "orders", "ship", "Card 3 declined", 3));
        rows.add(row("p3", "orders", "charge", "Timeout after 30s", 4));
        rows.add(row("p4", "refunds", "charge", "Card 4 declined", 5));

        var found = service(100, 10, 10).incidents(null);

        assertThat(found.truncated()).isFalse();
        assertThat(found.scanned()).isEqualTo(5);
        assertThat(found.groups()).hasSize(4);
        var first = found.groups().getFirst();
        assertThat(first.workflowDefinitionId()).isEqualTo("orders");
        assertThat(first.stepId()).isEqualTo("charge");
        assertThat(first.errorFamily()).isEqualTo("Card # declined");
        assertThat(first.failedSteps()).isEqualTo(2);
        assertThat(first.processes()).isEqualTo(2);
        assertThat(first.firstSeen()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 1));
        assertThat(first.lastSeen()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 2));
        assertThat(service(100, 10, 10).incidents("refunds").groups()).hasSize(1);
        assertThat(service(2, 10, 10).incidents(null).truncated()).isTrue();
        assertThat(service(100, 10, 10).incident(first.key())).isEqualTo(first);
        assertThat(service(100, 10, 10).incident("nope")).isNull();
    }

    @Test
    void retryingAGroupRetriesEachOfItsStepsAndAuditsThem() {
        rows.add(row("p1", "orders", "charge", "Card 1 declined", 1));
        rows.add(row("p2", "orders", "charge", "Card 2 declined", 2));
        rows.add(row("p3", "orders", "charge", "Card 3 declined", 3));
        var service = service(100, 2, 10);
        var key = service.incidents(null).groups().getFirst().key();

        var outcome = service.retryGroup(key, "Gateway fixed", ALICE);

        assertThat(outcome.requested()).isEqualTo(2);
        assertThat(outcome.truncated()).isTrue();
        assertThat(outcome.async()).isFalse();
        assertThat(dispatched).containsExactly(new RetryStepExecutionRequested("se-p1-charge", "p1"),
                new RetryStepExecutionRequested("se-p2-charge", "p2"));
        verify(audit, times(2)).performed(anyString(), eq("orders"), anyString(), eq(OperatorAudit.RETRY_INCIDENT),
                eq("alice"), eq("Gateway fixed"), anyString());
        verify(operations).authorizeOperation(eq("orders"), eq(ALICE), anyString());
    }

    @Test
    void aLargeGroupIsRetriedInTheBackground() {
        for (int i = 0; i < 5; i++) {
            rows.add(row("p" + i, "orders", "charge", "boom", i));
        }
        var service = service(100, 100, 2);
        var key = service.incidents(null).groups().getFirst().key();

        var outcome = service.retryGroup(key, "go", ALICE);

        assertThat(outcome.async()).isTrue();
        await().untilAsserted(() -> assertThat(dispatched).hasSize(5));
        service.stop();
    }

    @Test
    void refusals() {
        rows.add(row("p1", "orders", "charge", "boom", 1));
        var service = service(100, 10, 10);
        var key = service.incidents(null).groups().getFirst().key();

        assertThatThrownBy(() -> service.retryGroup(key, " ", ALICE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.retryGroup("nope", "r", ALICE))
                .isInstanceOf(IncidentService.IncidentNotFoundException.class);
        doThrow(FlowAuthorizationDeniedException.of("x", "alice", List.of(), List.of("ops")))
                .when(operations).authorizeOperation(any(), any(), any());
        assertThatThrownBy(() -> service.retryGroup(key, "r", ALICE)).isInstanceOf(FlowAuthorizationDeniedException.class);
        assertThat(dispatched).isEmpty();
    }

    static FailedStep row(String processId, String definition, String step, String error, int minute) {
        return new FailedStep(processId, "bk-" + processId, definition, step, "se-" + processId + "-" + step,
                StepExecutionStatus.ERROR, LocalDateTime.of(2026, 10, 1, 0, minute), error);
    }
}
