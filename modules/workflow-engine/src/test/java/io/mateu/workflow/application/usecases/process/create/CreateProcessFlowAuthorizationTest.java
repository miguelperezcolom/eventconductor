package io.mateu.workflow.application.usecases.process.create;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.aggregates.WorkflowStatus;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.FlowAuthorizationDeniedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUTHZ-FLOW-01..06 — who may start a process.
 *
 * <p>A definition declares the scopes and roles a caller must hold; this is where holding them, or
 * not, decides whether a process comes into existence. Checked at the use case rather than at each
 * door, so the answer is the same whether the request came from the page, from an upstream record or
 * from an MCP call.
 */
@ExtendWith(MockitoExtension.class)
class CreateProcessFlowAuthorizationTest {

    @Mock ProcessRepository processRepository;
    @Mock WorkflowDefinitionRepository workflowDefinitionRepository;
    @Mock StepExecutionRepository stepExecutionRepository;
    @Mock WorkflowMetrics workflowMetrics;

    @InjectMocks CreateProcessUseCase useCase;

    private static final String DEFINITION_ID = "wd-1";

    private WorkflowDefinition definitionRequiring(List<String> scopes, List<String> roles) {
        var step = new Step("step-1", DEFINITION_ID, StepType.ACTION, "Step 1", "Desc", null, null,
                null, false, "topic", null, null, null, null, 0, null, null, null, null, 0, 0, false,
                null, 0, null);
        return new WorkflowDefinition(DEFINITION_ID, "Payments", 1, "", false, 0, false, null, 0,
                List.of(step), false, WorkflowStatus.ACTIVE, WorkflowStatus.ACTIVE, scopes, roles, 0);
    }

    private void definitionIs(WorkflowDefinition definition) {
        lenient().when(workflowDefinitionRepository.findById(DEFINITION_ID))
                .thenReturn(Optional.of(definition));
    }

    private CreateProcessCommand startedBy(AuthorizationContext caller) {
        return new CreateProcessCommand("p-1", DEFINITION_ID, "BK-1",
                List.of(new Variable("amount", "100")), null, caller);
    }

    private void enforce(boolean enabled) {
        useCase.flowAuthorizationEnabled = enabled;
        // @Value is not processed in a plain unit test, and zero would make every child creation
        // read as "already too deep".
        useCase.maxProcessDepth = 20;
    }

    /** AUTHZ-FLOW-01. Holding everything the definition asks for starts the process. */
    @Test
    void aCallerHoldingWhatTheDefinitionRequiresMayStartIt() {
        enforce(true);
        definitionIs(definitionRequiring(List.of("payments:write"), List.of("operator")));

        assertThatCode(() -> useCase.handle(startedBy(
                new AuthorizationContext("ana", List.of("payments:write"), List.of("operator")))))
                .doesNotThrowAnyException();

        verify(processRepository).save(any());
    }

    /**
     * AUTHZ-FLOW-02. Missing one of two is missing. Requires-all rather than any, because "this
     * needs X and Y" is the only safe reading of two requirements written side by side.
     */
    @Test
    void aCallerMissingOneRequirementMayNotStartItAndNothingIsCreated() {
        enforce(true);
        definitionIs(definitionRequiring(List.of("payments:write", "payments:approve"), List.of()));

        assertThatThrownBy(() -> useCase.handle(startedBy(
                new AuthorizationContext("ana", List.of("payments:write"), List.of()))))
                .isInstanceOf(FlowAuthorizationDeniedException.class)
                .hasMessageContaining("payments:approve")
                .hasMessageContaining("ana");

        verify(processRepository, never()).save(any());
        verify(stepExecutionRepository, never()).save(any());
    }

    /**
     * AUTHZ-FLOW-03. Nobody is not somebody with no requirements. A caller the deployment could not
     * identify holds nothing, so a definition that requires anything refuses them — which is what
     * makes a misconfigured identity a locked door rather than an open one.
     */
    @Test
    void anUnidentifiedCallerIsRefusedByAnyRequirementAtAll() {
        enforce(true);
        definitionIs(definitionRequiring(List.of("payments:write"), List.of()));

        assertThatThrownBy(() -> useCase.handle(startedBy(null)))
                .isInstanceOf(FlowAuthorizationDeniedException.class)
                .hasMessageContaining("unidentified");
    }

    /** AUTHZ-FLOW-04. A definition that requires nothing is open, identified caller or not. */
    @Test
    void aDefinitionThatRequiresNothingIsOpenToEveryone() {
        enforce(true);
        definitionIs(definitionRequiring(List.of(), List.of()));

        assertThatCode(() -> useCase.handle(startedBy(null))).doesNotThrowAnyException();

        verify(processRepository).save(any());
    }

    /**
     * AUTHZ-FLOW-05. Off is off. The requirements are declarative and were parsed long before
     * anything enforced them, so a deployment that has never configured an identity must not find
     * its restricted definitions refused the moment it upgrades.
     */
    @Test
    void nothingIsEnforcedWhileTheFeatureIsOff() {
        enforce(false);
        definitionIs(definitionRequiring(List.of("payments:write"), List.of("operator")));

        assertThatCode(() -> useCase.handle(startedBy(null))).doesNotThrowAnyException();

        verify(processRepository).save(any());
    }

    /**
     * AUTHZ-FLOW-06. The engine starting something for itself is not a caller asking for it. Cron
     * says so with SYSTEM and a PROCESS step says so by naming the parent step execution; re-judging
     * either would mean a scheduled definition could never run and a modelled child could never be
     * spawned, which is breakage rather than authorization.
     */
    @Test
    void whatTheEngineStartsForItselfIsNotJudgedAgainstTheCaller() {
        enforce(true);
        definitionIs(definitionRequiring(List.of("payments:write"), List.of()));

        assertThatCode(() -> useCase.handle(startedBy(AuthorizationContext.SYSTEM)))
                .doesNotThrowAnyException();

        var child = new CreateProcessCommand("p-2", DEFINITION_ID, "parent:se-1", List.of(), "se-1", null);
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.empty());
        assertThatCode(() -> useCase.handle(child)).doesNotThrowAnyException();
    }
}
