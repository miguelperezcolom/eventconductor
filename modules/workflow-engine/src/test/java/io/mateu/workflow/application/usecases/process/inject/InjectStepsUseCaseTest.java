package io.mateu.workflow.application.usecases.process.inject;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessCommand;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.StepType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InjectStepsUseCaseTest {

    @Mock StepExecutionRepository stepExecutionRepository;
    @Mock ProcessRepository processRepository;
    @Mock LogMessageRepository logMessageRepository;
    @Mock StepOverProcessUseCase stepOverProcessUseCase;

    @InjectMocks InjectStepsUseCase useCase;

    @org.junit.jupiter.api.BeforeEach
    void applyDefaultBudget() {
        // @Value defaults are not injected outside a Spring context, so set the guard the same way
        // the property would. Tests that exercise the cap override it explicitly.
        org.springframework.test.util.ReflectionTestUtils.setField(useCase, "maxStepsPerProcess", 500);
    }

    private static final String PROCESS_ID = "p-1";
    private static final String WD_ID = "wd-1";

    // --- fixtures -----------------------------------------------------------------------------

    private Step step(String id, StepType type, String preconditionStepId) {
        return new Step(id, WD_ID, type, id, null, preconditionStepId, null, null, false,
                "topic", null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
    }

    private StepExecution se(String id, String stepId, StepType type, StepExecutionStatus status, int order) {
        var step = step(stepId, type, null);
        return StepExecution.builder()
                .id(id).processId(PROCESS_ID).workflowDefinitionId(WD_ID)
                .stepId(stepId).stepJson(JsonSerializer.toJson(step))
                .status(status).order(order).variables(List.of()).build();
    }

    private Process process() {
        return Process.builder().id(PROCESS_ID).workflowDefinitionId(WD_ID)
                .status(ProcessStatus.RUNNING).variables(List.of()).build();
    }

    private void wire(StepExecution injecting, List<StepExecution> existing) {
        when(stepExecutionRepository.findById(injecting.id())).thenReturn(Optional.of(injecting));
        lenient().when(processRepository.findById(PROCESS_ID)).thenReturn(Optional.of(process()));
        lenient().when(stepExecutionRepository.findByProcess(any())).thenReturn(existing);
    }

    private String json(Step... steps) {
        return JsonSerializer.toJson(List.of(steps));
    }

    // --- valid injection ----------------------------------------------------------------------

    @Test
    void injectsStepsWithContinuingPositionsAndPreservedPreconditions() {
        var injecting = se("se-dyn", "dyn", StepType.DYNAMIC, StepExecutionStatus.COMPLETED, 5);
        wire(injecting, List.of(injecting));

        var injectedA = step("a", StepType.ACTION, "dyn");
        var injectedB = step("b", StepType.ACTION, "a");
        useCase.handle(new InjectStepsCommand("se-dyn", json(injectedA, injectedB)));

        var captor = ArgumentCaptor.forClass(StepExecution.class);
        verify(stepExecutionRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        var saved = captor.getAllValues();
        assertThat(saved).extracting(StepExecution::getStepId).containsExactly("a", "b");
        // positions continue after the process's current max order (5).
        assertThat(saved).extracting(StepExecution::getOrder).containsExactly(6L, 7L);
        assertThat(saved).allSatisfy(se ->
                assertThat(se.getStatus()).isEqualTo(StepExecutionStatus.CREATED));
        // every injected step is stamped with the injecting DYNAMIC step's id (the idempotency key).
        assertThat(saved).allSatisfy(se ->
                assertThat(se.getInjectedByStepExecutionId()).isEqualTo("se-dyn"));
        // preconditions survive the round-trip.
        var stepB = JsonSerializer.pojoFromJson(saved.get(1).getStepJson(), Step.class);
        assertThat(stepB.preconditionIds()).containsExactly("a");
        // the process is re-evaluated so newly-ready injected steps get dispatched.
        verify(stepOverProcessUseCase).handle(new StepOverProcessCommand(PROCESS_ID));
    }

    // --- rejections ---------------------------------------------------------------------------

    @Test
    void rejectsWhenSourceStepIsNotDynamic() {
        var injecting = se("se-act", "act", StepType.ACTION, StepExecutionStatus.COMPLETED, 1);
        when(stepExecutionRepository.findById("se-act")).thenReturn(Optional.of(injecting));

        useCase.handle(new InjectStepsCommand("se-act", json(step("a", StepType.ACTION, "act"))));

        assertNothingInjectedAndSourceErrored(injecting);
    }

    @Test
    void rejectsDuplicateIdAgainstExistingStep() {
        var injecting = se("se-dyn", "dyn", StepType.DYNAMIC, StepExecutionStatus.COMPLETED, 2);
        var existing = se("se-x", "x", StepType.ACTION, StepExecutionStatus.COMPLETED, 1);
        wire(injecting, List.of(injecting, existing));

        useCase.handle(new InjectStepsCommand("se-dyn", json(step("x", StepType.ACTION, "dyn"))));

        assertNothingInjectedAndSourceErrored(injecting);
    }

    @Test
    void rejectsDuplicateIdAmongSiblings() {
        var injecting = se("se-dyn", "dyn", StepType.DYNAMIC, StepExecutionStatus.COMPLETED, 2);
        wire(injecting, List.of(injecting));

        useCase.handle(new InjectStepsCommand("se-dyn",
                json(step("dup", StepType.ACTION, "dyn"), step("dup", StepType.ACTION, "dyn"))));

        assertNothingInjectedAndSourceErrored(injecting);
    }

    @Test
    void rejectsDanglingPreconditionReference() {
        var injecting = se("se-dyn", "dyn", StepType.DYNAMIC, StepExecutionStatus.COMPLETED, 2);
        wire(injecting, List.of(injecting));

        useCase.handle(new InjectStepsCommand("se-dyn",
                json(step("a", StepType.ACTION, "does-not-exist"))));

        assertNothingInjectedAndSourceErrored(injecting);
    }

    @Test
    void rejectsCycleAmongInjectedSteps() {
        var injecting = se("se-dyn", "dyn", StepType.DYNAMIC, StepExecutionStatus.COMPLETED, 2);
        wire(injecting, List.of(injecting));

        // a -> b -> a
        useCase.handle(new InjectStepsCommand("se-dyn",
                json(step("a", StepType.ACTION, "b"), step("b", StepType.ACTION, "a"))));

        assertNothingInjectedAndSourceErrored(injecting);
    }

    @Test
    void rejectsWhenBudgetWouldBeExceeded() {
        // Drop the cap so a tiny fixture trips it: existing(1) + injected(2) > 2.
        org.springframework.test.util.ReflectionTestUtils.setField(useCase, "maxStepsPerProcess", 2);
        var injecting = se("se-dyn", "dyn", StepType.DYNAMIC, StepExecutionStatus.COMPLETED, 1);
        wire(injecting, List.of(injecting));

        useCase.handle(new InjectStepsCommand("se-dyn",
                json(step("a", StepType.ACTION, "dyn"), step("b", StepType.ACTION, "dyn"))));

        assertNothingInjectedAndSourceErrored(injecting);
    }

    // --- idempotency --------------------------------------------------------------------------

    @Test
    void redeliveryDoesNotDoubleInject() {
        var injecting = se("se-dyn", "dyn", StepType.DYNAMIC, StepExecutionStatus.COMPLETED, 5);
        // A child already exists: a step stamped as injected by this DYNAMIC step. The exact marker
        // is what makes the redelivery a no-op — no reliance on the precondition graph.
        var childStep = step("a", StepType.ACTION, "dyn");
        var alreadyInjected = StepExecution.builder()
                .id("se-a").processId(PROCESS_ID).workflowDefinitionId(WD_ID)
                .stepId("a").stepJson(JsonSerializer.toJson(childStep))
                .status(StepExecutionStatus.CREATED).order(6).variables(List.of())
                .injectedByStepExecutionId("se-dyn").build();
        wire(injecting, List.of(injecting, alreadyInjected));

        useCase.handle(new InjectStepsCommand("se-dyn", json(step("a", StepType.ACTION, "dyn"))));

        verify(stepExecutionRepository, never()).save(any());
        verify(stepOverProcessUseCase, never()).handle(any());
    }

    // --- helpers ------------------------------------------------------------------------------

    private void assertNothingInjectedAndSourceErrored(StepExecution injecting) {
        // The DYNAMIC step is failed and no injected step execution is created. (The failed
        // source step is itself saved, so we assert on the step id rather than on save count.)
        var captor = ArgumentCaptor.forClass(StepExecution.class);
        verify(stepExecutionRepository, org.mockito.Mockito.atMost(1)).save(captor.capture());
        captor.getAllValues().forEach(se ->
                assertThat(se.getId()).isEqualTo(injecting.getId()));
        assertThat(injecting.getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        verify(logMessageRepository).save(any());
        verify(stepOverProcessUseCase, never()).handle(any());
    }
}
