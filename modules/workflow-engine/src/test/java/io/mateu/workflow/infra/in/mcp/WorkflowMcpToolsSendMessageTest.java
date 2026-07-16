package io.mateu.workflow.infra.in.mcp;

import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WorkflowMcpToolsSendMessageTest {

    @Mock io.mateu.workflow.application.out.ProcessRepository processRepository;
    @Mock io.mateu.workflow.application.out.StepExecutionRepository stepExecutionRepository;
    @Mock io.mateu.workflow.application.out.LogMessageRepository logMessageRepository;
    @Mock io.mateu.workflow.application.usecases.process.retry.RetryProcessUseCase retryProcessUseCase;
    @Mock io.mateu.workflow.application.usecases.gitimport.ImportWorkflowDefinitionsFromGitUseCase importWorkflowDefinitionsFromGitUseCase;
    @Mock UpstreamEventPublisher upstreamEventPublisher;

    @InjectMocks WorkflowMcpTools tools;

    @Test
    void publishesAMessageReceivedUpstreamEvent() {
        var result = tools.sendMessage("payment-received", "bk-1", Map.of("paymentId", "P-9"));

        ArgumentCaptor<MessageReceived> captor = ArgumentCaptor.forClass(MessageReceived.class);
        verify(upstreamEventPublisher).publish(captor.capture());
        assertThat(captor.getValue().messageName()).isEqualTo("payment-received");
        assertThat(captor.getValue().correlationKey()).isEqualTo("bk-1");
        assertThat(captor.getValue().variables()).containsExactly(new Variable("paymentId", "P-9"));
        assertThat(result).contains("payment-received").contains("bk-1");
    }

    @Test
    void toleratesNullVariables() {
        tools.sendMessage("payment-received", "bk-1", null);

        ArgumentCaptor<MessageReceived> captor = ArgumentCaptor.forClass(MessageReceived.class);
        verify(upstreamEventPublisher).publish(captor.capture());
        assertThat(captor.getValue().variables()).isEmpty();
    }
}
