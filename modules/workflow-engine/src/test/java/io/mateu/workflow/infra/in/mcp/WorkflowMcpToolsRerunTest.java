package io.mateu.workflow.infra.in.mcp;

import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.dtos.events.integration.RestartProcessRequested;
import io.mateu.workflow.dtos.events.integration.RetryProcessRequested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * The two ways an agent can run a stopped process again. Both publish rather than act, for the
 * same reason the UI does: the call lands on whichever node took it, and the process belongs to
 * the one holding its partition.
 *
 * <p>Which of the two an agent picks is decided entirely by the tool descriptions, so those carry
 * the difference — where it stopped versus from the beginning — and the statuses each accepts.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowMcpToolsRerunTest {

    @Mock io.mateu.workflow.application.out.ProcessRepository processRepository;
    @Mock io.mateu.workflow.application.out.StepExecutionRepository stepExecutionRepository;
    @Mock io.mateu.workflow.application.out.LogMessageRepository logMessageRepository;
    @Mock io.mateu.workflow.application.usecases.process.retry.RetryProcessUseCase retryProcessUseCase;
    @Mock io.mateu.workflow.application.usecases.gitimport.ImportWorkflowDefinitionsFromGitUseCase importWorkflowDefinitionsFromGitUseCase;
    @Mock UpstreamEventPublisher upstreamEventPublisher;

    @InjectMocks WorkflowMcpTools tools;

    @Test
    void retryPublishesARetryRequestKeyedByTheProcess() {
        var result = tools.retryProcess("p-1");

        verify(upstreamEventPublisher).publish(new RetryProcessRequested("p-1"));
        assertThat(result).contains("p-1");
    }

    @Test
    void restartPublishesARestartRequestKeyedByTheProcess() {
        var result = tools.restartProcess("p-1");

        verify(upstreamEventPublisher).publish(new RestartProcessRequested("p-1"));
        assertThat(result).contains("p-1");
    }
}
